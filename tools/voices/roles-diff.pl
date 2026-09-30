#!/usr/bin/perl
# Compares two outputs of RealBookSpeakersTest (REAL_BOOK_ROLES) and prints every line whose voice
# changed: [chapter:paragraph] OLD -> NEW  text. Voices: M, F, ? (the narrator reads the line).
#   perl -CSD roles-diff.pl before.txt after.txt
use strict;
use warnings;
sub load {
    my ($file) = @_;
    my (%voice, %text, $chapter);
    open my $fh, '<:encoding(UTF-8)', $file or die "$file: $!";
    while (<$fh>) {
        chomp; s/\r$//;
        if (/^=== (\d+)/) { $chapter = $1; next }
        next unless /^(.{1,2}) #(\d+) (.*)/;
        my ($label, $n, $t) = ($1, $2, $3);
        $label =~ s/\s//g;
        $voice{"$chapter:$n"} = $label;
        $text{"$chapter:$n"} = $t;
    }
    return (\%voice, \%text);
}
my ($old) = load(shift);
my ($new, $text) = load(shift);
my %count;
for my $key (sort { my @a = split /:/, $a; my @b = split /:/, $b; $a[0] <=> $b[0] || $a[1] <=> $b[1] } keys %$old) {
    my ($from, $to) = ($old->{$key}, $new->{$key} // '');
    next if $from eq $to;
    $count{($from || '-') . '>' . ($to || '-')}++;
    printf "[%s] %s -> %s  %s\n", $key, $from || '-', $to || '-', substr($text->{$key} // '', 0, 150);
}
print STDERR join(', ', map { "$_ $count{$_}" } sort keys %count), "\n";
