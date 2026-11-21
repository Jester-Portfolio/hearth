package io.github.jesterportfolio.hearth.administration;
import java.nio.file.*;import java.nio.channels.*;import java.util.*;import java.io.*;import java.security.*;
/** 의존성 없는 기동 입력 검사. 역할·정지 상태·검토 해시를 검사하며 운영 승인은 별도로 요구한다. */
public final class NetworkLaunchCheck {
 public static String validate(Path root,Path manifest,String expected)throws Exception{return validate(root,manifest,expected,null);}
 public static String validate(Path root,Path manifest,String expected,String role)throws Exception{
  if(!root.toAbsolutePath().equals(root.toRealPath()))throw new IOException("NETWORK_ROOT");root=root.toRealPath();
  if(!manifest.toAbsolutePath().equals(manifest.toRealPath())||Files.size(manifest)>65536||!digest(manifest).equals(expected))throw new IOException("NETWORK_LAUNCH_MANIFEST");var p=new Properties();try(var in=Files.newBufferedReader(manifest)){p.load(in);}
  if(role==null?!"1".equals(p.getProperty("format")):!Set.of("proxy","lobby","economy").contains(role)||!"2".equals(p.getProperty("format"))||!role.equals(p.getProperty("role")))throw new IOException("NETWORK_ROLE");
  if(!"true".equals(p.getProperty("acceptance-complete")))throw new IOException("NETWORK_ACCEPTANCE_REQUIRED");String jar=p.getProperty("jar","");if(!jar.matches("[A-Za-z0-9_./-]+")||jar.contains(".."))throw new IOException("NETWORK_JAR");int count=0;
  for(String k:p.stringPropertyNames())if(k.startsWith("file.")){if(++count>128)throw new IOException("NETWORK_INPUT_LIMIT");String name=k.substring(5);Path relative=Path.of(name);if(relative.isAbsolute()||!name.matches("[A-Za-z0-9_./-]+")||name.contains(".."))throw new IOException("NETWORK_INPUT_PATH");Path f=root.resolve(name);if(!f.equals(f.toRealPath())||!Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)||!digest(f).equals(p.getProperty(k)))throw new IOException("NETWORK_INPUT_CHANGED");}
  if(count<1||!p.containsKey("file."+jar))throw new IOException("NETWORK_INPUTS");
  var channels=new ArrayList<FileChannel>();var locks=new ArrayList<FileLock>();try(var paths=Files.walk(root)){
   var all=paths.limit(100001).toList();if(all.size()>100000)throw new IOException("NETWORK_TREE_LIMIT");
   for(var path:all){String name=path.getFileName().toString();if(name.endsWith(".active")||name.equals("postmaster.pid")||Files.isSymbolicLink(path))throw new IOException("NETWORK_NOT_OFFLINE");if(name.equals("session.lock")){var c=FileChannel.open(path,StandardOpenOption.READ);channels.add(c);var lock=c.tryLock(0,Long.MAX_VALUE,true);if(lock==null)throw new IOException("NETWORK_WORLD_RUNNING");locks.add(lock);}}
  }finally{for(var lock:locks)lock.close();for(var c:channels)c.close();}
  return jar;
 }
 private static String digest(Path f)throws Exception{var d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(f)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
 public static void main(String[] args){try{if(args.length!=4||Runtime.version().feature()!=25)throw new IOException("NETWORK_JAVA25");System.out.println(validate(Path.of(args[0]),Path.of(args[1]),args[2],args[3]));}catch(Exception e){System.err.println("HEARTH_NETWORK_LAUNCH_REFUSED");System.exit(2);}}
}
