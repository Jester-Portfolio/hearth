package io.github.jesterportfolio.hearth.util;

/** 한 모듈의 종료 실패가 나머지 모듈의 정리를 건너뛰지 않도록 합니다. 동기 대기는 하지 않습니다. */
public final class ShutdownCleanup {
    private ShutdownCleanup(){}
    public static void close(AutoCloseable... modules){
        RuntimeException failed=null;
        for(var module:modules){
            if(module==null)continue;
            try{module.close();}catch(Exception error){
                if(failed==null)failed=new IllegalStateException("MODULE_CLEANUP_FAILED",error);
                else failed.addSuppressed(error);
            }
        }
        if(failed!=null)throw failed;
    }
}
