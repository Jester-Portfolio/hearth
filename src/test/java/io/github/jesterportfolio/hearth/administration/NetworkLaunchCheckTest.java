package io.github.jesterportfolio.hearth.administration;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.security.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class NetworkLaunchCheckTest {
 @TempDir Path root;
 String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
 @Test void exactReviewedArtifactIsRequiredAndChangedInputRefuses()throws Exception{Path jar=root.resolve("server.jar"),manifest=root.resolve("launch.properties");Files.writeString(jar,"jar");Files.writeString(manifest,"format=1\nacceptance-complete=true\njar=server.jar\nfile.server.jar="+hash(jar)+"\n");String seal=hash(manifest);assertEquals("server.jar",NetworkLaunchCheck.validate(root,manifest,seal));Files.writeString(jar,"other");assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,manifest,seal));}
 @Test void MissingAcceptanceAndManifestChangesRefuse()throws Exception{Path jar=root.resolve("server.jar"),manifest=root.resolve("launch.properties");Files.writeString(jar,"jar");Files.writeString(manifest,"format=1\nacceptance-complete=false\njar=server.jar\nfile.server.jar="+hash(jar)+"\n");assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,manifest,hash(manifest)));assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,manifest,"a".repeat(64)));}
 @Test void roleBoundManifestAndUncleanProxyMarkerAreRefused()throws Exception{Path jar=root.resolve("server.jar"),m=root.resolve("launch.properties");Files.writeString(jar,"jar");Files.writeString(m,"format=2\nrole=proxy\nacceptance-complete=true\njar=server.jar\nfile.server.jar="+hash(jar)+"\n");assertEquals("server.jar",NetworkLaunchCheck.validate(root,m,hash(m),"proxy"));assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"lobby"));Path marker=root.resolve("plugins/hearth-network/proxy-runtime.active");Files.createDirectories(marker.getParent());Files.writeString(marker,"unclean");assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"proxy"));assertEquals("unclean",Files.readString(marker));}
 @Test void actualWorldLockPreventsLaunch()throws Exception{Path jar=root.resolve("server.jar"),m=root.resolve("launch.properties"),lock=root.resolve("session.lock");Files.writeString(jar,"jar");Files.writeString(lock,"lock");Files.writeString(m,"format=2\nrole=economy\nacceptance-complete=true\njar=server.jar\nfile.server.jar="+hash(jar)+"\n");try(var c=java.nio.channels.FileChannel.open(lock,StandardOpenOption.WRITE);var held=c.lock()){assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"economy"));}}
 Path reviewedManifest()throws Exception{
  Path jar=root.resolve("server.jar"),head=root.resolve("acceptance-state/head.json"),cert=root.resolve("acceptance.properties"),m=root.resolve("launch.properties");Files.writeString(jar,"jar");Files.createDirectories(head.getParent());Files.writeString(head,"current acceptance head");
  Files.writeString(cert,"format=1\nscope="+"a".repeat(64)+"\ncomplete=true\nhead="+hash(head)+"\nclient="+"b".repeat(64)+"\nload="+"c".repeat(64)+"\nrestore="+"d".repeat(64)+"\nrelease_regression="+"e".repeat(64)+"\nrollback="+"f".repeat(64)+"\n");
  Files.writeString(m,"format=3\nrole=economy\nacceptance-complete=true\nscope="+"a".repeat(64)+"\nacceptance-sha256="+hash(cert)+"\njar=server.jar\nfile.server.jar="+hash(jar)+"\n");return m;
 }
 @Test void acceptanceHeadReplacementAndPendingResultBlockNewLaunch()throws Exception{
  Path m=reviewedManifest(),head=root.resolve("acceptance-state/head.json");assertEquals("server.jar",NetworkLaunchCheck.validate(root,m,hash(m),"economy"));String original=Files.readString(head);
  Files.writeString(head,"new failed result");assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"economy"));Files.writeString(head,original);
  Files.writeString(root.resolve("acceptance-state/pending.json"),"interrupted update");assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"economy"));assertTrue(Files.exists(root.resolve("acceptance-state/pending.json")));
 }
 @Test void certificateCannotBeBorrowedForAnotherScopeOrChangedAfterReview()throws Exception{
  Path m=reviewedManifest();String original=Files.readString(m);Files.writeString(m,original.replace("scope="+"a".repeat(64),"scope="+"b".repeat(64)));assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"economy"));
  Files.writeString(m,original);Files.writeString(root.resolve("acceptance.properties"),"complete=true");assertThrows(Exception.class,()->NetworkLaunchCheck.validate(root,m,hash(m),"economy"));
 }
}
