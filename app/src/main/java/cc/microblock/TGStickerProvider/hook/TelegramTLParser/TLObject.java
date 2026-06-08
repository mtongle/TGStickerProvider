package cc.microblock.TGStickerProvider.hook.TelegramTLParser;

public class TLObject {

    public static final int FLAG_0  = 1;       // 1
    public static final int FLAG_1  = 1 << 1;  // 2
    public static final int FLAG_2  = 1 << 2;  // 4
    public static final int FLAG_3  = 1 << 3;  // 8
    public static final int FLAG_4  = 1 << 4;  // 16
    public static final int FLAG_5  = 1 << 5;  // 32
    public static final int FLAG_6  = 1 << 6;  // 64
    public static final int FLAG_7  = 1 << 7;  // 128
    public static final int FLAG_8  = 1 << 8;  // 256
    public static final int FLAG_9  = 1 << 9;  // 512
    public static final int FLAG_10 = 1 << 10; // 1024
    public static final int FLAG_11 = 1 << 11; // 2048
    public static final int FLAG_12 = 1 << 12; // 4096
    public static final int FLAG_13 = 1 << 13; // 8192
    public static final int FLAG_14 = 1 << 14; // 16_384
    public static final int FLAG_15 = 1 << 15; // 32_768
    public static final int FLAG_16 = 1 << 16; // 65_536
    public static final int FLAG_17 = 1 << 17; // 131_072
    public static final int FLAG_18 = 1 << 18; // 262_144
    public static final int FLAG_19 = 1 << 19; // 524_288
    public static final int FLAG_20 = 1 << 20; // 1_048_576
    public static final int FLAG_21 = 1 << 21; // 2_097_152
    public static final int FLAG_22 = 1 << 22; // 4_194_304
    public static final int FLAG_23 = 1 << 23; // 8_388_608
    public static final int FLAG_24 = 1 << 24; // 16_777_216
    public static final int FLAG_25 = 1 << 25; // 33_554_432
    public static final int FLAG_26 = 1 << 26; // 67_108_864
    public static final int FLAG_27 = 1 << 27; // 134_217_728
    public static final int FLAG_28 = 1 << 28; // 268_435_456
    public static final int FLAG_29 = 1 << 29; // 536_870_912
    public static final int FLAG_30 = 1 << 30; // 107_374_1824
    public static final int FLAG_31 = 1 << 31; // 214_748_3648

    public int networkType;

    public boolean disableFree = false;

    public TLObject() {

    }

    public void readParams(AbstractSerializedData stream, boolean exception) {

    }

    public void serializeToStream(AbstractSerializedData stream) {

    }

    public TLObject deserializeResponse(AbstractSerializedData stream, int constructor, boolean exception) {
        return null;
    }

    public void freeResources() {

    }

    public int getObjectSize() {
        return -1;
    }
}