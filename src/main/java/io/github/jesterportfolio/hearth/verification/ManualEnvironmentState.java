package io.github.jesterportfolio.hearth.verification;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** 시험 월드의 최초 난이도를 불변 파일로 보존합니다. 다른 월드/손상/링크는 덮어쓰지 않습니다. */
public final class ManualEnvironmentState {
    private static final Set<String> VALUES=Set.of("PEACEFUL","EASY","NORMAL","HARD");
    public static String load(Path file,UUID run,UUID world,String initial) throws IOException {
        if(!VALUES.contains(initial))throw new IllegalArgumentException("CLIENT_DIFFICULTY");
        if(Files.isSymbolicLink(file))throw new IOException("CLIENT_ENVIRONMENT_LINK");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            var value=new Properties();value.setProperty("format","1");value.setProperty("run",run.toString());value.setProperty("world",world.toString());value.setProperty("difficulty",initial);
            try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){value.store(out,"isolated manual environment");}catch(FileAlreadyExistsException race){/* 첫 작성자의 원본을 아래에서 확인합니다. */}
        }
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>1024)throw new IOException("CLIENT_ENVIRONMENT_FILE");
        var saved=new Properties();try(var in=Files.newInputStream(file)){saved.load(in);}
        if(!"1".equals(saved.getProperty("format"))||!run.toString().equals(saved.getProperty("run"))||!world.toString().equals(saved.getProperty("world"))||!VALUES.contains(saved.getProperty("difficulty","")))throw new IOException("CLIENT_ENVIRONMENT_IDENTITY");
        return saved.getProperty("difficulty");
    }
}
