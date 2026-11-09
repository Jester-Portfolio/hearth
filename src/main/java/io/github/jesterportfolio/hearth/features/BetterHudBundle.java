package io.github.jesterportfolio.hearth.features;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Hearth 이름의 새 파일만 준비합니다. 기존 BetterHud 설정과 편집한 파일은 보존합니다. */
final class BetterHudBundle {
    static void install(JavaPlugin hearth,Plugin better) throws IOException {
        for(String relative:List.of("huds/hearth-s4.yml","layouts/hearth-s4.yml","texts/hearth-s4.yml","assets/hearth/minimap_pixel.png"))
            copy(hearth,"betterhud/"+relative,better.getDataFolder().toPath(),relative);
        try(var input=Objects.requireNonNull(hearth.getResource("betterhud/merge-files.txt"))) {
            var files=new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
            for(String relative:files)copy(hearth,"betterhud/merge/"+relative,hearth.getDataFolder().toPath().resolve("betterhud-resourcepack"),relative);
        }
    }
    private static void copy(JavaPlugin plugin,String source,Path root,String relative) throws IOException {
        if(relative.startsWith("/")||relative.contains("..")||relative.contains("\\"))throw new IOException("BETTERHUD_ASSET_PATH");
        Path target=root.resolve(relative);
        for(Path part=root;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IOException("BETTERHUD_ASSET_SYMLINK");
        Path directory=root;
        Files.createDirectories(directory);
        for(Path part:root.relativize(target.getParent())) {
            directory=directory.resolve(part);
            if(Files.isSymbolicLink(directory))throw new IOException("BETTERHUD_ASSET_SYMLINK");
            Files.createDirectories(directory);
        }
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("BETTERHUD_ASSET_NOT_REGULAR");
            return;
        }
        try(var input=Objects.requireNonNull(plugin.getResource(source));var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {input.transferTo(output);}
    }
}
