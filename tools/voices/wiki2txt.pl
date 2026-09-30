#!/usr/bin/perl
# Turns the wiki markup of a ru.wikisource page into plain text, one paragraph a line, for
# RealBookSpeakersTest (REAL_BOOK=file.txt).
#   perl -CSD wiki2txt.pl page.wiki > page.txt
use strict;
use warnings;
local $/;
my $t = <>;
1 while $t =~ s/\{\{[^{}]*\}\}//gs;                     # templates, innermost first
$t =~ s/<ref[^>]*\/>//gs;
$t =~ s/<ref.*?<\/ref>//gs;                               # footnotes
$t =~ s/<[^>]+>//g;                                       # other tags
$t =~ s/\[\[(?:[^|\]]*\|)?([^\]]*)\]\]/$1/g;              # [[link|text]] -> text
$t =~ s/'''?//g;                                          # bold, italics
$t =~ s/&nbsp;/ /g;
$t =~ s/^[=*#:;].*$//mg;                                  # headings, lists
$t =~ s/^\s*[|}].*$//mg;                                  # table rows
for my $p (split /\n\s*\n/, $t) {
    $p =~ s/\s+/ /g;
    $p =~ s/^\s+|\s+$//g;
    print "$p\n" if $p =~ /\p{L}{3}/;
}
