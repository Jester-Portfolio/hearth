package io.github.jesterportfolio.hearth.inventory;

import java.io.*;
import java.security.*;
import java.util.*;

/** 불변 41슬롯 봉투. NBT 해석은 Paper 메인 스레드의 코덱이 담당합니다. */
public final class InventoryPayload {
    public static final int SLOTS=41, MAX_BYTES=1024*1024, MAX_SLOT_BYTES=64*1024;
    private static final int MAGIC=0x48495631, VERSION=1, DIGEST=32;
    private final byte[] encoded;
    private InventoryPayload(byte[] encoded) { this.encoded=encoded; }
    public static InventoryPayload pack(byte[][] slots) {
        if (slots==null || slots.length!=SLOTS) throw invalid();
        try {
            var buffer=new ByteArrayOutputStream();
            var out=new DataOutputStream(buffer);
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(SLOTS);
            for (byte[] slot:slots) {
                if (slot==null) out.writeInt(-1);
                else {
                    if (slot.length==0 || slot.length>MAX_SLOT_BYTES) throw invalid();
                    out.writeInt(slot.length); out.write(slot);
                }
                if (buffer.size()+DIGEST>MAX_BYTES) throw invalid();
            }
            byte[] body=buffer.toByteArray(); out.write(hash(body));
            return new InventoryPayload(buffer.toByteArray());
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static InventoryPayload parse(byte[] bytes) {
        if (bytes==null || bytes.length<12+SLOTS*4+DIGEST || bytes.length>MAX_BYTES) throw invalid();
        byte[] copy=bytes.clone(), body=Arrays.copyOf(copy,copy.length-DIGEST);
        if (!MessageDigest.isEqual(hash(body),Arrays.copyOfRange(copy,body.length,copy.length))) throw invalid();
        try {
            var input=new DataInputStream(new ByteArrayInputStream(body));
            if (input.readInt()!=MAGIC || input.readInt()!=VERSION || input.readInt()!=SLOTS) throw invalid();
            for (int i=0;i<SLOTS;i++) {
                int size=input.readInt();
                if (size==-1) continue;
                if (size<=0 || size>MAX_SLOT_BYTES || size>input.available()) throw invalid();
                if (input.skipBytes(size)!=size) throw invalid();
            }
            if (input.available()!=0) throw invalid();
            return new InventoryPayload(copy);
        } catch (IOException malformed) { throw invalid(); }
    }
    public byte[][] slots() {
        try {
            var input=new DataInputStream(new ByteArrayInputStream(encoded)); input.skipNBytes(12);
            byte[][] slots=new byte[SLOTS][];
            for (int i=0;i<SLOTS;i++) { int size=input.readInt(); if (size!=-1) slots[i]=input.readNBytes(size); }
            return slots;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public byte[] bytes() { return encoded.clone(); }
    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_INVENTORY_PAYLOAD"); }
    @Override public boolean equals(Object other) { return other instanceof InventoryPayload p && Arrays.equals(encoded,p.encoded); }
    @Override public int hashCode() { return Arrays.hashCode(encoded); }
}
