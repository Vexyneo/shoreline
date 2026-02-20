package net.shoreline.client.impl.font;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.shoreline.client.api.font.Glyph;
import net.shoreline.client.api.font.GlyphCache;
import net.shoreline.client.impl.module.client.ColorsModule;
import net.shoreline.client.impl.module.client.FontModule;
import net.shoreline.client.impl.module.client.SocialsModule;
import net.shoreline.client.util.Globals;
import net.shoreline.client.util.math.HexRandom;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AWTFontRenderer - 한글(CJK) 폰트 fallback 지원 버전.
 *
 * == 변경 내용 ==
 *
 * 1. loadFallbackFont(): OS별 한글 폰트 자동 탐색
 *    - Linux: /usr/share/fonts 의 NotoSansCJK-Regular.ttc
 *    - Windows: C:/Windows/Fonts/malgun.ttf (맑은 고딕)
 *    - macOS: /Library/Fonts/AppleSDGothicNeo-Regular.otf
 *    - 모두 실패 시: Java 논리 폰트 "Dialog" (OS fontconfig 경유 한글 지원)
 *
 * 2. getGlyphFromChar(char c):
 *    - 기존: 항상 primary 폰트만으로 GlyphCache 생성
 *    - 수정: GlyphCache 생성 시 fallbackFont를 함께 전달
 *            → GlyphCache 내부에서 문자별로 최적 폰트 선택
 *
 * 3. createFont():
 *    - scale 변경 시 fallbackFont도 같은 size*scale 로 재생성
 */
public final class AWTFontRenderer implements Closeable, Globals
{
    private Font font;
    private final float size;

    /**
     * 한글·CJK 문자를 렌더링하기 위한 보조 폰트.
     * primary 폰트(Verdana 등)가 canDisplay() = false 인 문자에 사용된다.
     */
    private Font fallbackFont;

    private int scale;
    private int lastScale;

    private static final Pattern PATTERN_CONTROL_CODE =
            Pattern.compile("(?i)\\u00A7[0-9A-FK-OG]");

    private final ObjectList<GlyphCache> caches = new ObjectArrayList<>();
    private final Char2ObjectArrayMap<Glyph> glyphs = new Char2ObjectArrayMap<>();
    private final Map<Identifier, ObjectList<CharLocation>> cache =
            new Object2ObjectOpenHashMap<>();

    public AWTFontRenderer(InputStream inputStream, float size)
    {
        try
        {
            this.font = Font.createFont(Font.TRUETYPE_FONT, inputStream);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
            this.font = new Font("Verdana", Font.PLAIN, Math.round(size));
        }
        this.size = size;
        createFont(font, size);
    }

    public static String stripControlCodes(String text)
    {
        return PATTERN_CONTROL_CODE.matcher(text).replaceAll("");
    }

    /**
     * primary 폰트와 함께 한글 fallback 폰트를 초기화한다.
     *
     * fallback 탐색 순서:
     * 1. Linux: NotoSansCJK-Regular.ttc (시스템 설치된 경우)
     * 2. Windows: malgun.ttf (맑은 고딕)
     * 3. macOS: AppleSDGothicNeo
     * 4. Java "Dialog" 논리 폰트 (AWT가 OS fontconfig로 한글 지원)
     * 5. 시스템 폰트 중 한글(가) 표시 가능한 첫 번째 폰트
     */
    private void createFont(Font font, float size)
    {
        this.lastScale = (int) mc.getWindow().getScaleFactor();
        this.scale     = this.lastScale;
        this.font      = font.deriveFont(size * scale);

        // fallback 폰트 초기화
        this.fallbackFont = loadFallbackFont(size * scale);
    }

    /**
     * 한글을 지원하는 폰트를 OS에서 탐색해 반환한다.
     *
     * @param derivedSize 적용할 폰트 크기 (size * scale)
     * @return 한글 지원 Font 객체 (실패 시 Java 논리 폰트 "Dialog")
     */
    private Font loadFallbackFont(float derivedSize)
    {
        // ── OS 파일 직접 로드 (가장 정확한 한글 렌더링) ────────────
        String[] fontPaths = {
            // Linux (Noto Sans CJK)
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttf",
            // Linux (나눔고딕)
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            // Windows (맑은 고딕)
            "C:/Windows/Fonts/malgun.ttf",
            "C:/Windows/Fonts/malgunbd.ttf",
            // macOS (애플 SD 산돌고딕)
            "/Library/Fonts/AppleSDGothicNeo-Regular.otf",
            "/System/Library/Fonts/AppleSDGothicNeo.ttc",
        };

        for (String path : fontPaths)
        {
            try
            {
                File file = new File(path);
                if (!file.exists()) continue;
                Font loaded = Font.createFont(Font.TRUETYPE_FONT, file);
                // 한글 '가' 자 표시 가능 여부 검증
                if (loaded.canDisplay('가'))
                {
                    return loaded.deriveFont(derivedSize);
                }
            }
            catch (Throwable ignored) {}
        }

        // ── AWT 시스템 폰트 목록에서 한글 지원 폰트 탐색 ────────────
        String[] preferredNames = {
            "Noto Sans CJK KR",
            "NanumGothic",
            "NanumBarunGothic",
            "Malgun Gothic",
            "맑은 고딕",
            "Apple SD Gothic Neo",
            "나눔고딕",
        };
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> available =
                new java.util.HashSet<>(java.util.Arrays.asList(ge.getAvailableFontFamilyNames()));

        for (String name : preferredNames)
        {
            if (available.contains(name))
            {
                Font f = new Font(name, Font.PLAIN, Math.round(derivedSize));
                if (f.canDisplay('가'))
                    return f;
            }
        }

        // ── 시스템 폰트 전수 검색 (최후 수단) ────────────────────────
        for (String name : ge.getAvailableFontFamilyNames())
        {
            Font f = new Font(name, Font.PLAIN, Math.round(derivedSize));
            if (f.canDisplay('가') && f.canDisplay('나') && f.canDisplay('다'))
                return f;
        }

        // ── Java 논리 폰트 "Dialog" ───────────────────────────────────
        // AWT는 OS의 fontconfig/font.properties를 경유해
        // Dialog를 렌더링할 때 한글 지원 시스템 폰트를 사용한다.
        return new Font("Dialog", Font.PLAIN, Math.round(derivedSize));
    }

    // ── drawString 오버로드들 (기존과 동일) ──────────────────────────

    public void drawStringWithShadow(MatrixStack stack, String text, double x, double y, int color)
    {
        drawString(stack, text, x + 0.5f, y + 0.5f, color, true);
        drawString(stack, text, x, y, color, false);
    }

    public void drawString(MatrixStack stack, String text, double x, double y, int color)
    {
        drawString(stack, text, x, y, color, false);
    }

    private void drawString(MatrixStack stack, String text, double x, double y, int color, boolean shadow)
    {
        float brightnessMultiplier = shadow ? 0.25f : 1.0f;
        float r = ((color >> 16) & 0xff) / 255.0f * brightnessMultiplier;
        float g = ((color >>  8) & 0xff) / 255.0f * brightnessMultiplier;
        float b = ((color      ) & 0xff) / 255.0f * brightnessMultiplier;
        float a = ((color >> 24) & 0xff) / 255.0f;
        drawString(stack, text, (float) x, (float) y, r, g, b, a, brightnessMultiplier);
    }

    public void drawString(MatrixStack stack, String text, double x, double y, Color color)
    {
        drawString(stack, text, x, y, color, false);
    }

    public void drawString(MatrixStack stack, String text, double x, double y,
                           Color color, boolean shadow)
    {
        float bm = shadow ? 0.25f : 1.0f;
        drawString(stack, text, (float) x, (float) y,
                color.getRed() / 255.0f * bm,
                color.getGreen() / 255.0f * bm,
                color.getBlue() / 255.0f * bm,
                color.getAlpha(), bm);
    }

    public void drawString(MatrixStack stack, String text, float x, float y,
                           float r, float g, float b, float a, float brightnessMultiplier)
    {
        // Scale 변경 감지 → 폰트 재생성 (fallback 포함)
        int currentScale = (int) mc.getWindow().getScaleFactor();
        if (currentScale != lastScale)
        {
            close();
            createFont(font, size);
        }

        float r2 = r, g2 = g, b2 = b;
        stack.push();
        y -= 3.0f;
        stack.translate(x, y, 0.0f);
        stack.scale(1.0f / scale, 1.0f / scale, 0.0f);

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        Tessellator tessellator = Tessellator.getInstance();
        Matrix4f matrix4f = stack.peek().getPositionMatrix();
        char[] chars = text.toCharArray();
        float xOffset = 0;
        float yOffset = 0;
        boolean formatting = false;
        int lineStart = 0;
        glyphs.clear();

        synchronized (cache)
        {
            for (int i = 0; i < chars.length; i++)
            {
                char c = chars[i];
                if (formatting)
                {
                    formatting = false;
                    if (c == 'r')
                    {
                        r2 = r; g2 = g; b2 = b;
                    }
                    else
                    {
                        int colorCode = getColorFromCode(c);
                        int[] col = toRgbComponents(colorCode);
                        r2 = col[0] / 255.0f * brightnessMultiplier;
                        g2 = col[1] / 255.0f * brightnessMultiplier;
                        b2 = col[2] / 255.0f * brightnessMultiplier;
                    }
                    continue;
                }
                if (c == '§')      { formatting = true; continue; }
                if (c == '\n')
                {
                    yOffset += getStringHeight(text.substring(lineStart, i)) * scale;
                    xOffset = 0;
                    lineStart = i + 1;
                    continue;
                }

                Glyph glyph = glyphs.computeIfAbsent(c, ch -> getGlyphFromChar(ch));
                if (glyph != null)
                {
                    if (glyph.value() != ' ')
                    {
                        Identifier i1 = glyph.owner().getId();
                        cache.computeIfAbsent(i1, id -> new ObjectArrayList<>())
                             .add(new CharLocation(xOffset, yOffset, r2, g2, b2, glyph));
                    }
                    xOffset += glyph.width();
                }
            }

            for (Identifier identifier : cache.keySet())
            {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.disableCull();
                RenderSystem.texParameter(GL11.GL_TEXTURE_2D,
                        GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                RenderSystem.texParameter(GL11.GL_TEXTURE_2D,
                        GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                try
                {
                    RenderSystem.setShaderTexture(0, identifier);
                }
                catch (Exception e) { continue; }

                List<CharLocation> objects = cache.get(identifier);
                BufferBuilder bufferBuilder = tessellator.begin(
                        VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

                for (CharLocation object : objects)
                {
                    float xo = object.x, yo = object.y;
                    float cr = object.r, cg = object.g, cb = object.b;
                    Glyph glyph = object.glyph;
                    GlyphCache owner = glyph.owner();
                    float w  = glyph.width(), h = glyph.height();
                    float u1 = (float)  glyph.textureWidth()              / owner.getWidth();
                    float v1 = (float)  glyph.textureHeight()             / owner.getHeight();
                    float u2 = (float) (glyph.textureWidth()  + glyph.width())  / owner.getWidth();
                    float v2 = (float) (glyph.textureHeight() + glyph.height()) / owner.getHeight();
                    bufferBuilder.vertex(matrix4f, xo + 0, yo + h, 0).color(cr, cg, cb, a).texture(u1, v2);
                    bufferBuilder.vertex(matrix4f, xo + w, yo + h, 0).color(cr, cg, cb, a).texture(u2, v2);
                    bufferBuilder.vertex(matrix4f, xo + w, yo + 0, 0).color(cr, cg, cb, a).texture(u2, v1);
                    bufferBuilder.vertex(matrix4f, xo + 0, yo + 0, 0).color(cr, cg, cb, a).texture(u1, v1);
                }
                BufferRenderer.drawWithGlobalProgram(bufferBuilder.end());
                RenderSystem.disableBlend();
            }
            cache.clear();
        }
        stack.pop();
    }

    // ── drawCenteredString ───────────────────────────────────────────

    public void drawCenteredString(MatrixStack stack, String text, double x, double y, int color)
    {
        drawString(stack, text, x, y, color, false);
    }

    public void drawCenteredString(MatrixStack stack, String text, double x, double y,
                                   int color, boolean shadow)
    {
        float bm = shadow ? 0.25f : 1.0f;
        float r  = ((color >> 16) & 0xff) / 255.0f * bm;
        float g  = ((color >>  8) & 0xff) / 255.0f * bm;
        float b  = ((color      ) & 0xff) / 255.0f * bm;
        float a  = (color & 0xff000000) != 0xff000000
                ? 1.0f : ((color >> 24) & 0xff) / 255.0f * bm;
        drawString(stack, text, (float) (x - getStringWidth(text) / 2f), (float) y, r, g, b, a, bm);
    }

    public void drawCenteredString(MatrixStack stack, String text, double x, double y, Color color)
    {
        drawString(stack, text, x, y, color, false);
    }

    public void drawCenteredString(MatrixStack stack, String text, double x, double y,
                                   Color color, boolean shadow)
    {
        float bm = shadow ? 0.25f : 1.0f;
        drawString(stack, text, (float) (x - getStringWidth(text) / 2.0f), (float) y,
                color.getRed()   / 255.0f * bm,
                color.getGreen() / 255.0f * bm,
                color.getBlue()  / 255.0f * bm,
                color.getAlpha() / 255.0f, bm);
    }

    // ── 문자 폭/높이 계산 ─────────────────────────────────────────────

    public float getStringWidth(String text)
    {
        char[] c = stripControlCodes(text).toCharArray();
        float currentLine = 0, maxPrev = 0;
        for (char c1 : c)
        {
            if (c1 == '\n')
            {
                maxPrev = Math.max(currentLine, maxPrev);
                currentLine = 0;
                continue;
            }
            Glyph glyph = glyphs.computeIfAbsent(c1, ch -> getGlyphFromChar(ch));
            currentLine += glyph == null ? 0 : glyph.width() / (float) scale;
        }
        return Math.max(currentLine, maxPrev);
    }

    public float getStringHeight(String text)
    {
        char[] c = stripControlCodes(text).toCharArray();
        if (c.length == 0) c = new char[]{' '};
        float currentLine = 0, previous = 0;
        for (char c1 : c)
        {
            if (c1 == '\n')
            {
                if (currentLine == 0)
                {
                    Glyph glyph = glyphs.computeIfAbsent(' ', ch -> getGlyphFromChar(ch));
                    currentLine = glyph == null ? 0 : glyph.height() / (float) scale;
                }
                previous += currentLine;
                currentLine = 0;
                continue;
            }
            Glyph glyph = glyphs.computeIfAbsent(c1, ch -> getGlyphFromChar(ch));
            float h = glyph == null ? 0 : glyph.height();
            currentLine = Math.max(h / (float) scale, currentLine);
        }
        return currentLine + previous;
    }

    public float getFontHeight() { return size; }

    // ── GlyphCache 관리 ───────────────────────────────────────────────

    /**
     * 문자 c에 해당하는 Glyph를 반환한다.
     *
     * 기존: GlyphCache(base, base+256, font, ...) — primary 폰트만 사용
     * 수정: GlyphCache(base, base+256, font, fallbackFont, ...) — fallback 전달
     *       GlyphCache 내부에서 canDisplay()로 문자별 최적 폰트 선택
     */
    private Glyph getGlyphFromChar(char c)
    {
        // 이미 만들어진 캐시에 있으면 반환
        for (GlyphCache map : caches)
        {
            if (map.contains(c)) return map.getGlyph(c);
        }

        // 새 256자 블록 캐시 생성 (fallbackFont 포함)
        int base = 256 * (int) Math.floor((double) c / 256.0);
        GlyphCache glyphCache = new GlyphCache(
                (char) base, (char) (base + 256),
                font,                                          // primary
                fallbackFont,                                  // ★ fallback 추가
                getGlyphIdentifier(),
                5,
                FontModule.getInstance().getAntiAlias(),
                FontModule.getInstance().getFractionalMetrics()
        );
        caches.add(glyphCache);
        return glyphCache.getGlyph(c);
    }

    @Override
    public void close()
    {
        try
        {
            for (GlyphCache cache1 : caches) cache1.clear();
            caches.clear();
            glyphs.clear();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public Identifier getGlyphIdentifier()
    {
        return Identifier.of("shoreline", "font/storage/" + HexRandom.generateRandomHex(32));
    }

    public int[] toRgbComponents(int color)
    {
        return new int[]{
            (color >> 16) & 0xff,
            (color >>  8) & 0xff,
            (color      ) & 0xff,
            (color & 0xff000000) != 0xff000000 ? 255 : (color >> 24) & 0xff
        };
    }

    private int getColorFromCode(char code)
    {
        return switch (code)
        {
            case '0' -> Color.BLACK.getRGB();
            case '1' -> 0xff0000AA;
            case '2' -> 0xff00AA00;
            case '3' -> 0xff00AAAA;
            case '4' -> 0xffAA0000;
            case '5' -> 0xffAA00AA;
            case '6' -> 0xffFFAA00;
            case '7' -> 0xffAAAAAA;
            case '8' -> 0xff555555;
            case '9' -> 0xff5555FF;
            case 'a' -> 0xff55FF55;
            case 'b' -> 0xff55FFFF;
            case 'c' -> 0xffFF5555;
            case 'd' -> 0xffFF55FF;
            case 'e' -> 0xffFFFF55;
            case 'f' -> 0xffffffff;
            case 's' -> ColorsModule.getInstance().getRGB();
            case 'g' -> SocialsModule.getInstance().getFriendRGB();
            default  -> -1;
        };
    }

    public record CharLocation(float x, float y, float r, float g, float b, Glyph glyph) {}
}
