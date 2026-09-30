#!/usr/bin/perl
# Shows each changed line from roles-diff.pl with the two paragraphs before it and the one after,
# for reading the changes by eye.
#   perl -CSD roles-diff.pl before.txt after.txt > changes.txt
#   perl -CSD roles-context.pl changes.txt after.txt 'F -> M|M -> F'
use strict;
use warnings;
my ($changes, $roles, $filter) = @ARGV;
$filter //= '.';
my %want;
open my $c, '<:encoding(UTF-8)', $changes or die "$changes: $!";
while (<$c>) {
    next unless /^\[(\d+):(\d+)\] (\S+ -> \S+)/;
    my ($chapter, $n, $change) = ($1, $2, $3);
    $want{"$chapter:$n"} = $change if $change =~ /$filter/;
}
open my $r, '<:encoding(UTF-8)', $roles or die "$roles: $!";
my ($chapter, @before, $after);
while (<$r>) {
    chomp; s/\r$//;
    if (/^=== (\d+)/) { $chapter = $1; @before = (); next }
    next unless /^(.{1,2}) #(\d+) (.*)/;
    my $line = "$1 #$2 " . substr($3, 0, 220);
    if ($after) { print "  $line\n"; $after = 0 }
    if (my $change = $want{"$chapter:$2"}) {
        print "==== [$chapter:$2] $change\n", map({ "  $_\n" } @before), ">>$line\n";
        $after = 1;
    }
    push @before, $line;
    shift @before while @before > 2;
}
