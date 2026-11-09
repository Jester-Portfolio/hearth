package io.github.jesterportfolio.hearth.features;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 현재 팩의 성공 응답만 허용합니다. 재접속/팩 변경의 이전 응답은 새 연결을 열지 못합니다. */
public final class HudPackGate<P> {
    private UUID expected;
    private final Map<P,Set<UUID>> loaded=new IdentityHashMap<>();
    public void expect(UUID id){expected=Objects.requireNonNull(id);}
    public UUID expected(){return expected;}
    public void status(P actor,UUID id,boolean success){
        if(id==null)return;
        if(success) {
            if(loaded.size()>=128&&!loaded.containsKey(actor))return;
            var packs=loaded.computeIfAbsent(actor,k->new LinkedHashSet<>());
            if(packs.size()>=8&&!packs.contains(id)){var old=packs.stream().filter(p->!p.equals(expected)).findFirst();old.ifPresent(packs::remove);}
            packs.add(id);
        }else {var packs=loaded.get(actor);if(packs!=null){packs.remove(id);if(packs.isEmpty())loaded.remove(actor);}}
    }
    public boolean allowed(P actor){return expected!=null&&loaded.getOrDefault(actor,Set.of()).contains(expected);}
    public void forget(P actor){loaded.remove(actor);}
    public void clear(){loaded.clear();}
    public static UUID contentId(Map<String,byte[]> entries) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(var key:new TreeSet<>(entries.keySet())) {
                byte[] name=key.getBytes(StandardCharsets.UTF_8),data=entries.get(key);
                digest.update(ByteBuffer.allocate(4).putInt(name.length).array());digest.update(name);
                digest.update(ByteBuffer.allocate(4).putInt(data.length).array());digest.update(data);
            }
            return UUID.nameUUIDFromBytes(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
