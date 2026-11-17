package io.github.jesterportfolio.hearth.administration;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** JDK 전용 기동 전 읽기 검사. Bukkit/DB/네트워크/외부 라이브러리를 초기화하지 않습니다. */
public final class OperatorLaunchCheck {
    private OperatorLaunchCheck(){}
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException("LAUNCH_REFUSED");}
    private static Path file(Path root,String relative)throws Exception{require(relative.matches("[A-Za-z0-9_./-]+")&&!relative.startsWith("/")&&!Arrays.asList(relative.split("/")).contains(".."));var path=root.resolve(relative).normalize();for(var part:path)require(!part.toString().equals("hearth-story-private"));require(path.startsWith(root)&&path.equals(path.toRealPath())&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS));return path;}
    private static String hash(Path file)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var input=new DigestInputStream(Files.newInputStream(file),digest)){input.transferTo(OutputStream.nullOutputStream());}return HexFormat.of().formatHex(digest.digest());}
    private static Properties properties(Path path)throws Exception{require(Files.size(path)<=1024*1024);var props=new Properties();try(var input=Files.newBufferedReader(path)){props.load(input);}return props;}
    public static String check(Path root,Path manifest,String expectedHash,String expectedMode)throws Exception{
        root=root.toAbsolutePath().normalize();manifest=manifest.toAbsolutePath().normalize();for(var part:root)require(!part.toString().equals("hearth-story-private"));for(var part:manifest)require(!part.toString().equals("hearth-story-private"));require(root.equals(root.toRealPath())&&manifest.equals(manifest.toRealPath())&&expectedHash.matches("[a-f0-9]{64}")&&hash(manifest).equals(expectedHash));
        var props=properties(manifest);require(props.stringPropertyNames().equals(Set.of("format","mode","server_jar","password_environment","checksums_sha256"))&&props.getProperty("format").equals("1")&&Set.of("story","economy").contains(expectedMode)&&expectedMode.equals(props.getProperty("mode")));
        var sums=file(manifest.getParent(),"deployment.sha256");require(Files.size(sums)<=256*1024&&hash(sums).equals(props.getProperty("checksums_sha256")));var seen=new HashSet<String>();for(String line:Files.readAllLines(sums)){require(line.matches("[a-f0-9]{64}  [A-Za-z0-9_./-]+"));String relative=line.substring(66);require(seen.add(relative)&&hash(file(root,relative)).equals(line.substring(0,64)));}require(seen.contains("plugins/Hearth/config.yml")&&seen.contains("plugins/Hearth.jar")&&seen.contains(props.getProperty("server_jar")));
        var server=properties(file(root,"server.properties"));require("true".equals(server.getProperty("online-mode"))&&"true".equals(server.getProperty("white-list"))&&"true".equals(server.getProperty("enforce-whitelist"))&&"127.0.0.1".equals(server.getProperty("server-ip"))&&"world".equals(server.getProperty("level-name","world"))&&"false".equals(server.getProperty("enable-rcon","false"))&&"false".equals(server.getProperty("enable-query","false"))&&"true".equals(properties(file(root,"eula.txt")).getProperty("eula")));
        int port=Integer.parseInt(server.getProperty("server-port"));require(port>=1024&&port<=65535);for(String marker:List.of("story-runtime.active","economy-runtime.active"))require(!Files.exists(root.resolve("plugins/Hearth/"+marker),LinkOption.NOFOLLOW_LINKS));
        require(props.getProperty("password_environment").matches("[A-Z][A-Z0-9_]{0,63}"));return props.getProperty("server_jar")+"\n"+props.getProperty("password_environment");
    }
    public static void main(String[] args){try{require(args.length==4);System.out.println(check(Path.of(args[0]),Path.of(args[1]),args[2],args[3]));}catch(Exception error){System.err.println("OPERATOR_LAUNCH_CHECK REFUSED");System.exit(2);}}
}
