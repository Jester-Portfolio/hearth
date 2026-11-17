package io.github.jesterportfolio.hearth.administration;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class OperatorLaunchCheckTest {
    @TempDir Path temp;Path root,review;String expected;
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @BeforeEach void fixture()throws Exception{root=Files.createDirectory(temp.resolve("data"));review=Files.createDirectory(temp.resolve("review"));Files.createDirectories(root.resolve("plugins/Hearth"));var contents=new LinkedHashMap<String,String>();contents.put("plugins/Hearth/config.yml","reviewed config");contents.put("plugins/Hearth.jar","reviewed plugin");contents.put("server.jar","reviewed Paper");StringBuilder sums=new StringBuilder();for(var e:contents.entrySet()){Files.writeString(root.resolve(e.getKey()),e.getValue());sums.append(hash(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))).append("  ").append(e.getKey()).append('\n');}Files.writeString(review.resolve("deployment.sha256"),sums);Files.writeString(root.resolve("server.properties"),"online-mode=true\nwhite-list=true\nenforce-whitelist=true\nserver-ip=127.0.0.1\nserver-port=25586\n");Files.writeString(root.resolve("eula.txt"),"eula=true\n");var manifest="format=1\nmode=story\nserver_jar=server.jar\npassword_environment=HEARTH_TEST_SECRET\nchecksums_sha256="+hash(sums.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\n";Files.writeString(review.resolve("deployment.properties"),manifest);expected=hash(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    String check()throws Exception{return OperatorLaunchCheck.check(root,review.resolve("deployment.properties"),expected,"story");}
    @Test void reviewed_files_allow_only_fixed_jar_and_environment_name()throws Exception{assertEquals("server.jar\nHEARTH_TEST_SECRET",check());}
    @Test void changed_static_file_or_manifest_is_refused()throws Exception{Files.writeString(root.resolve("server.jar"),"changed");assertThrows(Exception.class,this::check);Files.writeString(review.resolve("deployment.properties"),"changed");assertThrows(Exception.class,this::check);}
    @Test void authenticated_loopback_properties_are_rechecked_after_audit()throws Exception{Files.writeString(root.resolve("server.properties"),"online-mode=false\nwhite-list=true\nenforce-whitelist=true\nserver-ip=127.0.0.1\nserver-port=25586\n");assertThrows(Exception.class,this::check);}
    @Test void existing_unclean_marker_is_preserved_and_refuses_launch()throws Exception{var marker=root.resolve("plugins/Hearth/story-runtime.active");Files.writeString(marker,"original evidence");assertThrows(Exception.class,this::check);assertEquals("original evidence",Files.readString(marker));}
    @Test void symlink_cannot_replace_reviewed_static_file()throws Exception{var jar=root.resolve("server.jar");Files.move(jar,temp.resolve("original.jar"));Files.createSymbolicLink(jar,temp.resolve("original.jar"));assertThrows(Exception.class,this::check);}
    @Test void other_runtime_mode_is_refused()throws Exception{assertThrows(Exception.class,()->OperatorLaunchCheck.check(root,review.resolve("deployment.properties"),expected,"economy"));}
    @Test void explicit_eula_and_nonprivileged_game_port_are_required()throws Exception{Files.writeString(root.resolve("eula.txt"),"eula=false\n");assertThrows(Exception.class,this::check);Files.writeString(root.resolve("eula.txt"),"eula=true\n");Files.writeString(root.resolve("server.properties"),Files.readString(root.resolve("server.properties")).replace("25586","80"));assertThrows(Exception.class,this::check);}
}
