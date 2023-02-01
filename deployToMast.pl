#!/usr/bin/perl --
use strict;
use warnings;
use feature qw(say);
use Getopt::Long;

my $verbose =1;
GetOptions(
    "verbose|v:+" => \$verbose,
    ) or die "bad options.";

# systemやcloseの結果を整形する
sub cmdResult($){
    my($rv)=@_;
    if( $rv == 0 ){
        return;
    }elsif( $rv == -1 ){
        return "failed to execute: $!";
    }elsif ( $rv & 127 ) {
        return sprintf "signal %d", ($rv & 127);
    }else {
        return sprintf "exitCode %d",($rv>>8);
    }
}

sub cmd($){
    $verbose and say $_[0];
    system $_[0];
    my $error = cmdResult $?;
    $error and die "$error cmd=$_[0]";
}

sub chdirOrThrow($){
    my($dir)=@_;
    chdir($dir) or die "chdir failed. $dir $!";
}

cmd qq(./gradlew shadowJar --stacktrace );

my $jarSrc = `ls -1t build/libs/*-all.jar|head -n 1`;
$jarSrc =~ s/\s+\z//;
(-f $jarSrc) or die "missing jarSrc [$jarSrc]";

my $appServerDir = "/m/subwaytooter-app-server/appServer2";
cmd qq(rsync -e ssh $jarSrc mast:$appServerDir/appFiles/appServer.jar);
cmd qq(ssh mast "cd $appServerDir && ./restartWeb.pl");
