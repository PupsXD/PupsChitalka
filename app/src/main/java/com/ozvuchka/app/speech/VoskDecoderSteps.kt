package com.ozvuchka.app.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Halves the work of Vosk TTS 0.10's acoustic model on the phone: its flow-matching decoder runs 10
 * Euler steps of dt = 0.1, each with two estimator passes mixed by classifier-free guidance; this keeps
 * steps 0, 2, 4, 6, 8 with dt = 0.2 and lets ONNX Runtime skip the rest. A port of
 * tools/voices/vosk-fewer-steps.py --steps 5. Measured on a Galaxy S24 Ultra: load 55 s → 14–18 s,
 * RTF 0.82 → 0.37–0.46; UTMOS on four voices 3.32 → 3.29.
 *
 * The .onnx file is protobuf. Only the node list changes, so nodes are parsed, a few inputs rewired,
 * five Constant nodes added and unneeded nodes left out; everything else, the 230 MB of weights
 * included, is copied byte for byte.
 */
internal object VoskDecoderSteps {
    private const val GRAPH = 7       // ModelProto.graph
    private const val NODE = 1        // GraphProto.node
    private const val GRAPH_OUTPUT = 12
    private val VELOCITY = Regex("/matcha/decoder/estimator(?:_(\\d+))?/Mul_1_output_0")

    private class Node(val start: Int, val end: Int, val inputs: MutableList<String>, val outputs: List<String>,
                       val name: String, val opType: String, val scalar: Float?) {
        var changed = false
    }

    /** Writes the 5-step model to [target]; throws, leaving [source] as it is, when the layout is not Vosk 0.10's. */
    fun halve(source: File, target: File) {
        RandomAccessFile(source, "r").use { file ->
            val bytes = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            val model = fields(bytes, 0, bytes.limit())
            val graphField = model.single { it.number == GRAPH }
            val graph = fields(bytes, graphField.valueStart, graphField.end)
            val nodeFields = graph.filter { it.number == NODE }
            val nodes = nodeFields.map { parseNode(bytes, it.valueStart, it.end) }
            val outputs = graph.filter { it.number == GRAPH_OUTPUT }
                .map { output -> string(bytes, fields(bytes, output.valueStart, output.end).first { it.number == 1 }) }
            val added = rewire(nodes, outputs)
            val kept = needed(nodes + added.map { it.first }, outputs)

            // The new graph as pieces: bytes built here, or ranges copied straight from the source file.
            val pieces = ArrayList<Any>()
            val buffered = ByteArrayOutputStream()
            var nodeIndex = 0
            for (field in graph) {
                when {
                    field.number == NODE -> {
                        if (nodeIndex == 0) added.forEach { (node, encoded) -> if (node in kept) buffered.writeField(NODE, encoded) }
                        val node = nodes[nodeIndex++]
                        if (node !in kept) continue
                        if (node.changed) buffered.writeField(NODE, encodeNode(bytes, node)) else buffered.writeRaw(bytes, field.start, field.end)
                    }
                    field.end - field.start > 1 shl 16 -> { // a weight tensor: copied without passing through memory
                        pieces += buffered.toByteArray()
                        buffered.reset()
                        pieces.add(field.start until field.end)
                    }
                    else -> buffered.writeRaw(bytes, field.start, field.end)
                }
            }
            pieces += buffered.toByteArray()
            val graphLength = pieces.sumOf { if (it is ByteArray) it.size.toLong() else (it as IntRange).count().toLong() }

            val partial = File(target.path + ".part")
            RandomAccessFile(partial, "rw").use { out ->
                out.setLength(0)
                val channel = out.channel
                fun write(buffer: ByteBuffer) { while (buffer.hasRemaining()) channel.write(buffer) }
                for (field in model) {
                    if (field.number != GRAPH) {
                        write(slice(bytes, field.start, field.end))
                        continue
                    }
                    write(ByteBuffer.wrap(ByteArrayOutputStream().apply { writeTag(GRAPH, 2); writeVarint(graphLength) }.toByteArray()))
                    for (piece in pieces) {
                        if (piece is ByteArray) write(ByteBuffer.wrap(piece))
                        else (piece as IntRange).let { write(slice(bytes, it.first, it.last + 1)) }
                    }
                }
            }
            if (!partial.renameTo(target)) {
                target.delete()
                check(partial.renameTo(target)) { "cannot write ${target.name}" }
            }
        }
    }

    /** The decoder edits; returns the new Constant nodes with their encodings. */
    private fun rewire(nodes: List<Node>, graphOutputs: List<String>): List<Pair<Node, ByteArray>> {
        val consumers = HashMap<String, MutableList<Node>>()
        nodes.forEach { node -> node.inputs.forEach { consumers.getOrPut(it) { ArrayList() } += node } }
        val constants = nodes.filter { it.opType == "Constant" }.associateBy { it.outputs.single() }
        class Step(val index: Int, val scaled: Node, val update: Node, val dt: String)
        val steps = nodes.filter { it.opType == "Sub" && it.name.startsWith("/matcha/decoder/Sub") }.mapNotNull { sub ->
            val cond = VELOCITY.matchEntire(sub.inputs[0]) ?: return@mapNotNull null
            VELOCITY.matchEntire(sub.inputs[1]) ?: return@mapNotNull null
            val weight = consumers[sub.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val mix = consumers[weight.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val scaled = consumers[mix.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val update = consumers[scaled.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val dt = scaled.inputs.first { it != mix.outputs[0] }
            Step((cond.groupValues[1].ifEmpty { "0" }.toInt()) / 2, scaled, update, dt)
        }.sortedBy { it.index }
        check(steps.map { it.index } == (0 until 10).toList()) { "unexpected decoder: steps ${steps.map { it.index }}" }
        check(steps.all { kotlin.math.abs((constants[it.dt]?.scalar ?: 0f) - 0.1f) < 1e-5f }) { "unexpected decoder: dt is not 0.1" }

        val added = ArrayList<Pair<Node, ByteArray>>()
        for (step in steps) {
            if (step.index % 2 == 0) {
                val name = "${step.dt}_x2"
                val encoded = constantNode(name, 0.2f)
                added += Node(-1, -1, ArrayList(), listOf(name), name, "Constant", 0.2f) to encoded
                step.scaled.inputs[step.scaled.inputs.indexOf(step.dt)] = name
                step.scaled.changed = true
            } else {
                val skipped = step.update.outputs[0]
                check(skipped !in graphOutputs) { "the last step cannot be skipped" }
                val previous = step.update.inputs.first { it != step.scaled.outputs[0] }
                consumers[skipped].orEmpty().forEach { node ->
                    for (i in node.inputs.indices) if (node.inputs[i] == skipped) node.inputs[i] = previous
                    node.changed = true
                }
            }
        }
        return added
    }

    /** Nodes some graph output depends on. */
    private fun needed(nodes: List<Node>, outputs: List<String>): Set<Node> {
        val producer = HashMap<String, Node>()
        nodes.forEach { node -> node.outputs.forEach { producer[it] = node } }
        val needed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        val stack = ArrayDeque(outputs)
        while (stack.isNotEmpty()) {
            val node = producer[stack.removeLast()] ?: continue
            if (needed.add(node)) node.inputs.forEach { if (it.isNotEmpty()) stack.addLast(it) }
        }
        return needed
    }

    // ------------------------------------------------------------------------------ protobuf

    private class Field(val number: Int, val wireType: Int, val start: Int, val valueStart: Int, val end: Int)

    /** The fields of the message in bytes [from, to). */
    private fun fields(bytes: ByteBuffer, from: Int, to: Int): List<Field> {
        val result = ArrayList<Field>()
        var at = from
        while (at < to) {
            val start = at
            val (tag, afterTag) = varint(bytes, at)
            at = afterTag
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            val valueStart: Int
            when (wireType) {
                0 -> { valueStart = at; at = varint(bytes, at).second }
                1 -> { valueStart = at; at += 8 }
                2 -> {
                    val (length, afterLength) = varint(bytes, at)
                    valueStart = afterLength
                    at = afterLength + length.toInt()
                }
                5 -> { valueStart = at; at += 4 }
                else -> error("unsupported protobuf wire type $wireType")
            }
            check(at <= to) { "truncated protobuf" }
            result += Field(number, wireType, start, valueStart, at)
        }
        return result
    }

    private fun varint(bytes: ByteBuffer, from: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var at = from
        while (true) {
            val b = bytes.get(at++).toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result to at
            shift += 7
        }
    }

    private fun string(bytes: ByteBuffer, field: Field): String = copy(bytes, field.valueStart, field.end).decodeToString()

    private fun copy(bytes: ByteBuffer, from: Int, to: Int): ByteArray = ByteArray(to - from).also { slice(bytes, from, to).get(it) }

    private fun parseNode(bytes: ByteBuffer, from: Int, to: Int): Node {
        val inputs = ArrayList<String>()
        val outputs = ArrayList<String>()
        var name = ""
        var opType = ""
        var scalar: Float? = null
        for (field in fields(bytes, from, to)) {
            when (field.number) {
                1 -> inputs += string(bytes, field)
                2 -> outputs += string(bytes, field)
                3 -> name = string(bytes, field)
                4 -> opType = string(bytes, field)
                5 -> scalar = scalar ?: attributeScalar(bytes, field)
            }
        }
        return Node(from, to, inputs, outputs, name, opType, scalar)
    }

    /** A Constant's `value` when it is one float32 (raw_data of 4 bytes or one float_data). */
    private fun attributeScalar(bytes: ByteBuffer, attribute: Field): Float? {
        val tensor = fields(bytes, attribute.valueStart, attribute.end).firstOrNull { it.number == 5 } ?: return null
        var type = 0L
        var value: Float? = null
        for (field in fields(bytes, tensor.valueStart, tensor.end)) {
            when {
                field.number == 2 -> type = varint(bytes, field.valueStart).first
                field.number == 9 && field.end - field.valueStart == 4 ->
                    value = slice(bytes, field.valueStart, field.end).order(ByteOrder.LITTLE_ENDIAN).float
                field.number == 4 && field.wireType == 2 && field.end - field.valueStart == 4 ->
                    value = slice(bytes, field.valueStart, field.end).order(ByteOrder.LITTLE_ENDIAN).float
                field.number == 4 && field.wireType == 5 ->
                    value = slice(bytes, field.valueStart, field.end).order(ByteOrder.LITTLE_ENDIAN).float
            }
        }
        return if (type == 1L) value else null
    }

    /** The node's own bytes with its inputs replaced by [Node.inputs], in order. */
    private fun encodeNode(bytes: ByteBuffer, node: Node): ByteArray {
        val out = ByteArrayOutputStream()
        var input = 0
        for (field in fields(bytes, node.start, node.end)) {
            if (field.number == 1) out.writeField(1, node.inputs[input++].encodeToByteArray())
            else out.writeRaw(bytes, field.start, field.end)
        }
        check(input == node.inputs.size) { "input count changed in ${node.name}" }
        return out.toByteArray()
    }

    /** `Constant` producing a float32 scalar, as onnx.helper.make_node would write it. */
    private fun constantNode(output: String, value: Float): ByteArray {
        val tensor = ByteArrayOutputStream().apply {
            writeTag(2, 0); writeVarint(1) // data_type FLOAT
            writeField(9, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }.toByteArray()
        val attribute = ByteArrayOutputStream().apply {
            writeField(1, "value".encodeToByteArray())
            writeField(5, tensor)
            writeTag(20, 0); writeVarint(4) // type TENSOR
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeField(2, output.encodeToByteArray())
            writeField(3, output.encodeToByteArray())
            writeField(4, "Constant".encodeToByteArray())
            writeField(5, attribute)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeTag(number: Int, wireType: Int) = writeVarint((number.toLong() shl 3) or wireType.toLong())

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.writeField(number: Int, value: ByteArray) {
        writeTag(number, 2)
        writeVarint(value.size.toLong())
        write(value)
    }

    private fun ByteArrayOutputStream.writeRaw(bytes: ByteBuffer, from: Int, to: Int) = write(copy(bytes, from, to))

    private fun slice(bytes: ByteBuffer, from: Int, to: Int): ByteBuffer =
        (bytes.duplicate() as ByteBuffer).apply { position(from); limit(to) }.slice()
}
