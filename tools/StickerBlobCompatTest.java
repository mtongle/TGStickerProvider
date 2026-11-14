import cc.microblock.TGStickerProvider.hook.TelegramTLParser.SerializedData;
import cc.microblock.TGStickerProvider.hook.TelegramTLParser.TLRPC;

import java.util.ArrayList;

/**
 * Compatibility check for the sticker-set blobs that modern Telegram forks (layer 13.2.1, e.g.
 * NagramXF) write into cache4.db: stickers_v2(row.data) = int32 count + TL_messages_stickerSet.
 * The blob below is generated independently of this codebase, so a parser that only knows the
 * older layer fails here instead of silently skipping whole sticker sets at runtime.
 *
 * Run: javac -d out $(find app/src/main/java/cc/microblock/TGStickerProvider/hook/TelegramTLParser -name '*.java') tools/StickerBlobCompatTest.java
 *      java -cp out StickerBlobCompatTest
 */
public class StickerBlobCompatTest {

    private static final String BLOB_HEX =
            "01000000163f156edc4ed12d90080000e764a5fcd09301004d01000000000000105465737420537469636b65722053657400" +
            "0000075465737453657415c4b51c0100000095fb3efa016d0000640000006400000015c4b51c03000000bc02000078050000" +
            "340800000200000007000000020000007856341215c4b51c01000000d499b21204f09f988000000015c4b51c020000003dce" +
            "7a08c5f801009211591cc5f8010015c4b51c010000009cb2fefc3dce7a08c5f8010015c4b51c010000000568617070790000" +
            "15c4b51c02000000d8c4d48f010000003dce7a08c5f801006f00000000000000040102030400000000105e5f0a696d616765" +
            "2f7765627000b82200000000000015c4b51c01000000608ec775016d00000002000000020000393000000200000015c4b51c" +
            "04000000f7d201985cc1376c000200000002000012d619630000000004f09f9880000000a0c81c8607546573745365746800" +
            "59150c737469636b65722e77656270000000d8c4d48f030000009211591cc5f80100de000000000000000405060708000000" +
            "64105e5f0a766964656f2f7765626d009f8601000000000015c4b51c01000000608ec775016d000000020000000200003930" +
            "000015c4b51c0100000094b033de0100000001760000000200000002000031d4000000000000000000000400000015c4b51c" +
            "04000000f7d201985cc1376c000200000002000012d619630000000004f09f9880000000a0c81c860754657374536574487c" +
            "c5430100000000000000000008400002000000020000";

    private static byte[] blob() {
        int n = BLOB_HEX.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(BLOB_HEX.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static int failures = 0;

    static void check(String what, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + ": expected=" + expected + " actual=" + actual);
        } else {
            System.out.println("ok   " + what + " = " + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] data = blob();
        SerializedData stream = new SerializedData(data);

        int count = stream.readInt32(true);
        int constructorId = -1;
        if (TLRPC.TL_messages_stickerSet.isStickerSetConstructor(count)) {
            constructorId = count;
            count = stream.readInt32(true);
        }
        check("count", count, 1);

        ArrayList<TLRPC.TL_messages_stickerSet> sets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i != 0 || constructorId == -1) constructorId = stream.readInt32(true);
            if (!TLRPC.TL_messages_stickerSet.isStickerSetConstructor(constructorId)) {
                throw new RuntimeException("not a sticker set: " + Integer.toHexString(constructorId));
            }
            sets.add(TLRPC.TL_messages_stickerSet.TLdeserialize(stream, constructorId, true));
        }

        TLRPC.TL_messages_stickerSet s = sets.get(0);
        check("short_name", s.set.short_name, "TestSet");
        check("title", s.set.title, "Test Sticker Set");
        check("set.count", s.set.count, 2);
        check("set.hash", s.set.hash, 0x12345678);
        check("set.flags creator bit", (s.set.flags & 2048) != 0, true);
        check("set.thumbs", s.set.thumbs.size(), 1);
        check("set thumb class", s.set.thumbs.get(0).getClass().getSimpleName(), "TL_photoSizeProgressive");
        check("set thumb size", s.set.thumbs.get(0).size, 2100);
        check("packs", s.packs.size(), 1);
        check("pack documents", s.packs.get(0).documents.size(), 2);
        check("keywords", s.keywords.size(), 1);
        check("documents", s.documents.size(), 2);

        TLRPC.Document d0 = s.documents.get(0);
        check("doc0 id", d0.id, 555000111222333L);
        check("doc0 mime", d0.mime_type, "image/webp");
        check("doc0 dc_id", d0.dc_id, 2);
        check("doc0 thumbs", d0.thumbs.size(), 1);
        check("doc0 thumb location volume", d0.thumbs.get(0).location.volume_id, -555000111222333L);
        check("doc0 thumb location local (1000+'m')", d0.thumbs.get(0).location.local_id, 1109);
        check("doc0 attrs", d0.attributes.size(), 4);
        boolean hasStickers = false;
        StringBuilder attrNames = new StringBuilder();
        for (TLRPC.DocumentAttribute a : d0.attributes) {
            attrNames.append(a.getClass().getSimpleName()).append(' ');
            if ("TL_documentAttributeHasStickers".equals(a.getClass().getSimpleName())) hasStickers = true;
        }
        check("doc0 attribute classes", attrNames.toString().trim(),
                "TL_documentAttributeHasStickers TL_documentAttributeImageSize TL_documentAttributeSticker TL_documentAttributeFilename");
        check("doc0 has documentAttributeHasStickers", hasStickers, true);

        TLRPC.Document d1 = s.documents.get(1);
        check("doc1 id", d1.id, 555000444555666L);
        check("doc1 mime", d1.mime_type, "video/webm");
        check("doc1 dc_id", d1.dc_id, 4);
        // video_thumbs only exists in the patched parser -> reflect so this harness also compiles
        // against the unpatched upstream sources for an A/B comparison.
        java.lang.reflect.Field vt;
        try {
            vt = TLRPC.Document.class.getField("video_thumbs");
        } catch (NoSuchFieldException e) {
            vt = null;
        }
        if (vt == null) {
            System.out.println("info document.video_thumbs is not modelled by this parser");
        } else {
            ArrayList<?> vthumbs = (ArrayList<?>) vt.get(d1);
            check("doc1 video_thumbs", vthumbs.size(), 1);
            check("doc1 video_thumb class", vthumbs.get(0).getClass().getSimpleName(), "TL_videoSize");
            java.lang.reflect.Field ts = vthumbs.get(0).getClass().getField("video_start_ts");
            check("doc1 video_thumb start_ts", ts.getDouble(vthumbs.get(0)), 0.0);
        }
        check("doc1 attrs", d1.attributes.size(), 4);

        if (stream.remaining() != 0) {
            failures++;
            System.out.println("FAIL trailing bytes: " + stream.remaining());
        } else {
            System.out.println("ok   no trailing bytes (stream in sync)");
        }

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
