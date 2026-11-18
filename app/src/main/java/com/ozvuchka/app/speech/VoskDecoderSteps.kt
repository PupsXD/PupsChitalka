package com.ozvuchka.app.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Rewrites Vosk TTS 0.10's acoustic model so it runs fewer, larger Euler steps.
 *
 * As published, the flow-matching decoder runs 10 steps of dt = 0.1, each with two estimator passes
 * mixed by classifier-free guidance: 20 passes, about 95% of the synthesis time. [rewrite] keeps
 * [STEPS] of the 10 steps, the first [GUIDED_STEPS] of them with guidance, and gives a skipped step's
 * dt to the step before it, so the trajectory still ends at t = 1.
 *
 * The defaults are the variant the PC stand picked (tools/voices/vosk-compare-variants.py, ten voices,
 * noise 0 and the model's own): 5 steps with guidance, 10 passes, the closest to the published model
 * by UTMOS. Measured alternatives on the same model, of which neither is faster and as close:
 * 8 steps of which 3 guided (11 passes, UTMOS −0.05…−0.09, log-mel 21% closer to the original) and
 * 8 steps without guidance (8 passes, 17% faster, UTMOS −0.10…−0.22, twice as far). See the PUP-003
 * report in docs/VOICE_CANDIDATES.md.
 *
 * The .onnx file is protobuf. Only the node list changes, so nodes are parsed, a few inputs rewired,
 * a couple of Constant nodes added and unneeded nodes and weights left out; everything else is copied
 * byte for byte. Accepts the model as published and the same graph after onnxruntime's
 * quant_pre_process, which stores the dt constants as initializers instead of Constant nodes.
 */
internal object VoskDecoderSteps {
    /** Steps kept out of the 10 the model has: 5 passes with guidance instead of 20. */
    const val STEPS = 5

    /** How many of the kept steps run the estimator twice for guidance: all of them. */
    const val GUIDED_STEPS = 5

    private const val STEPS_IN_MODEL = 10
    private const val GRAPH = 7       // ModelProto.graph
    private const val NODE = 1        // GraphProto.node
    private const val INITIALIZER = 5 // GraphProto.initializer
    private const val GRAPH_OUTPUT = 12
    private val VELOCITY = Regex("/matcha/decoder/estimator(?:_(\\d+))?/Mul_1_output_0")

    private class Node(val start: Int, val end: Int, val inputs: MutableList<String>, val outputs: List<String>,
                       val name: String, val opType: String, val scalar: Float?) {
        var changed = false
    }

    /**
     * Writes a [steps]-step model to [target]; throws, leaving [source] as it is, when the layout is
     * not Vosk 0.10's. The first [cfgSteps] kept steps keep classifier-free guidance, the rest use the
     * conditional velocity alone. Returns how many Constant nodes it added for the summed dt.
     */
    fun rewrite(source: File, target: File, steps: Int = STEPS, cfgSteps: Int = GUIDED_STEPS): Int {
        require(steps in 1..STEPS_IN_MODEL && cfgSteps in 0..steps) { "steps $steps, cfgSteps $cfgSteps" }
        var constantsAdded = 0
        RandomAccessFile(source, "r").use { file ->
            val bytes = file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            val model = fields(bytes, 0, bytes.limit())
            val graphField = model.single { it.number == GRAPH }
            val graph = fields(bytes, graphField.valueStart, graphField.end)
            val nodeFields = graph.filter { it.number == NODE }
            val nodes = nodeFields.map { parseNode(bytes, it.valueStart, it.end) }
            val outputs = graph.filter { it.number == GRAPH_OUTPUT }
                .map { output -> string(bytes, fields(bytes, output.valueStart, output.end).first { it.number == 1 }) }
            val scalars = initializerScalars(bytes, graph)
            val added = rewire(nodes, outputs, scalars, steps, cfgSteps)
            constantsAdded = added.size
            val kept = needed(nodes + added.map { it.first }, outputs)
            val used = HashSet<String>()
            kept.forEach { node -> used += node.inputs }

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
                    // The weights of the steps that are gone: several MB each, dropped whole.
                    field.number == INITIALIZER && field.wireType == 2 && initializerName(bytes, field)?.let { it !in used } == true ->
                        continue
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
        return constantsAdded
    }

    /** The decoder edits; returns the new Constant nodes with their encodings. */
    private fun rewire(
        nodes: List<Node>,
        graphOutputs: List<String>,
        initializers: Map<String, Float>,
        steps: Int,
        cfgSteps: Int,
    ): List<Pair<Node, ByteArray>> {
        val consumers = HashMap<String, MutableList<Node>>()
        nodes.forEach { node -> node.inputs.forEach { consumers.getOrPut(it) { ArrayList() } += node } }
        val constants = nodes.filter { it.opType == "Constant" }.associateBy { it.outputs.single() }
        class Step(val index: Int, val velocity: String, val mix: String, val scaled: Node, val update: Node, val dt: String) {
            val dtValue: Float? get() = constants[dt]?.scalar ?: initializers[dt]
        }
        val stepsFound = nodes.filter { it.opType == "Sub" && it.name.startsWith("/matcha/decoder/Sub") }.mapNotNull { sub ->
            val cond = VELOCITY.matchEntire(sub.inputs[0]) ?: return@mapNotNull null
            VELOCITY.matchEntire(sub.inputs[1]) ?: return@mapNotNull null
            val weight = consumers[sub.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val mix = consumers[weight.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val scaled = consumers[mix.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val update = consumers[scaled.outputs[0]]?.singleOrNull() ?: return@mapNotNull null
            val dt = scaled.inputs.first { it != mix.outputs[0] }
            Step((cond.groupValues[1].ifEmpty { "0" }.toInt()) / 2, sub.inputs[0], mix.outputs[0], scaled, update, dt)
        }.sortedBy { it.index }
        check(stepsFound.map { it.index } == (0 until STEPS_IN_MODEL).toList()) { "unexpected decoder: steps ${stepsFound.map { it.index }}" }
        check(stepsFound.all { kotlin.math.abs((it.dtValue ?: 0f) - 0.1f) < 1e-5f }) { "unexpected decoder: dt is not 0.1" }

        // Kept steps are spread over the ten: for 8 that is 0,1,2,3,5,6,7,8 and the last one covers 8→10.
        val kept = (0 until steps).map { it * STEPS_IN_MODEL / steps }.toSet()
        check(kept.size == steps) { "unexpected step selection: $kept" }

        val added = ArrayList<Pair<Node, ByteArray>>()
        val addedNames = HashSet<String>()
        for (step in stepsFound) {
            if (step.index !in kept) {
                // Whoever read this step's x now reads the x the step started from.
                val skipped = step.update.outputs[0]
                check(skipped !in graphOutputs) { "the last step cannot be skipped" }
                val previous = step.update.inputs.first { it != step.scaled.outputs[0] }
                consumers[skipped].orEmpty().forEach { node ->
                    for (i in node.inputs.indices) if (node.inputs[i] == skipped) node.inputs[i] = previous
                    node.changed = true
                }
                continue
            }
            // A kept step that covers several original steps integrates with the summed dt.
            val following = (kept.filter { it > step.index }.minOrNull() ?: STEPS_IN_MODEL)
            val multiplier = following - step.index
            if (multiplier != 1) {
                // Some steps share one dt constant, so this node may already be there: same name, same value.
                val name = "${step.dt}_x$multiplier"
                if (addedNames.add(name)) {
                    val value = 0.1f * multiplier
                    added += Node(-1, -1, ArrayList(), listOf(name), name, "Constant", value) to constantNode(name, value)
                }
                step.scaled.inputs[step.scaled.inputs.indexOf(step.dt)] = name
                step.scaled.changed = true
            }
        }

        // Guidance only where it was asked for; elsewhere the conditional velocity is used as it is.
        var guided = 0
        for (step in stepsFound) {
            if (step.index !in kept) continue
            if (guided++ < cfgSteps) continue
            step.scaled.inputs[step.scaled.inputs.indexOf(step.mix)] = step.velocity
            step.scaled.changed = true
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

    /** The name of an initializer's tensor, or null when the message is not one. */
    private fun initializerName(bytes: ByteBuffer, initializer: Field): String? =
        fields(bytes, initializer.valueStart, initializer.end).firstOrNull { it.number == 8 }?.let { string(bytes, it) }

    /** Float32 scalars among the graph initializers: quant_pre_process turns the dt constants into these. */
    private fun initializerScalars(bytes: ByteBuffer, graph: List<Field>): Map<String, Float> {
        val result = HashMap<String, Float>()
        for (field in graph) {
            if (field.number != INITIALIZER || field.wireType != 2) continue
            var name: String? = null
            var value: Float? = null
            for (tensor in fields(bytes, field.valueStart, field.end)) {
                when {
                    tensor.number == 8 -> name = string(bytes, tensor)
                    // raw_data (9) or one packed float_data (4), either way four bytes of a float32 scalar
                    tensor.number == 9 && tensor.end - tensor.valueStart == 4 ->
                        value = slice(bytes, tensor.valueStart, tensor.end).order(ByteOrder.LITTLE_ENDIAN).float
                    tensor.number == 4 && tensor.wireType == 2 && tensor.end - tensor.valueStart == 4 ->
                        value = slice(bytes, tensor.valueStart, tensor.end).order(ByteOrder.LITTLE_ENDIAN).float
                    tensor.number == 4 && tensor.wireType == 5 ->
                        value = slice(bytes, tensor.valueStart, tensor.end).order(ByteOrder.LITTLE_ENDIAN).float
                }
            }
            if (name != null && value != null) result[name] = value
        }
        return result
    }

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
