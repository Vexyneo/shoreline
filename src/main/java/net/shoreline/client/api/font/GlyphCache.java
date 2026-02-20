package net.shoreline.client.api.font;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.chars.Char2ObjectArrayMap;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.shoreline.client.mixin.accessor.AccessorNativeImage;
import net.shoreline.client.util.Globals;
import org.lwjgl.system.MemoryUtil;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.WritableRaster;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GlyphCache - 한글(CJK) 폰트 fallback 지원 버전.
 *
 * 변경 내용:
 * - fallbackFont 필드 추가: primary 폰트가 렌더 불가한 문자를 fallback으로 처리
 * - createBitmap()에서 문자별로 canDisplay() 체크 → 적합한 폰트 선택
 * - 한글, 일본어, 중국어 등 CJK 문자 정상 렌더링
 */
public class GlyphCache implements Globals
{
    private final char start, end;

    // 기본 폰트 (영문/숫자 등)
    private final Font font;

    // 보조 폰트 (한글·CJK 등 primary가 지원 못하는 문자에 사용)
    // null이면 fallback 없이 primary 폰트만 사용
    private final Font fallbackFont;

    private final Identifier id;
    private final int padding;
    private int width, height;
    private final Char2ObjectArrayMap<Glyph> glyphs = new Char2ObjectArrayMap<>();
    private boolean generated;

    private final boolean antiAlias, fractionalMetrics;

    // 기존 호환용 생성자
    public GlyphCache(char from, char to, Font font, Identifier id, int padding,
                      boolean antiAlias, boolean fractionalMetrics)
    {
        this(from, to, font, null, id, padding, antiAlias, fractionalMetrics);
    }

    // fallback 폰트 포함 생성자
    public GlyphCache(char from, char to, Font font, Font fallbackFont, Identifier id,
                      int padding, boolean antiAlias, boolean fractionalMetrics)
    {
        this.start           = from;
        this.end             = to;
        this.font            = font;
        this.fallbackFont    = fallbackFont;
        this.id              = id;
        this.padding         = padding;
        this.antiAlias       = antiAlias;
        this.fractionalMetrics = fractionalMetrics;
    }

    public Glyph getGlyph(char c)
    {
        if (!generated) createBitmap();
        return glyphs.get(c);
    }

    public void clear()
    {
        mc.getTextureManager().destroyTexture(id);
        glyphs.clear();
        generated = false;
    }

    public boolean contains(char c)
    {
        return c >= start && c < end;
    }

    /**
     * 이 블록(start~end)의 모든 문자에 대해 텍스처 아틀라스를 생성한다.
     *
     * 핵심 변경:
     * 각 문자마다 primaryFont.canDisplay(c)를 검사해
     * 렌더 가능한 폰트를 선택한다. Verdana처럼 한글 글리프가 없는 폰트는
     * 한글 문자에 대해 canDisplay() = false를 반환하므로
     * fallbackFont(예: Noto Sans CJK KR)로 대체 렌더링한다.
     */
    public void createBitmap()
    {
        if (generated) return;

        FontRenderContext frc = new FontRenderContext(
                new AffineTransform(), antiAlias, fractionalMetrics);

        // 1단계: 각 문자의 크기 측정 + 폰트 결정
        // [charCode, useFont, texX, texY, w, h]
        List<long[]> entryData = new ArrayList<>();  // [c, texX, texY, w, h]
        List<Font> entryFonts  = new ArrayList<>();  // 위치 대응

        int range   = end - start - 1;
        int ceiling = (int) (Math.ceil(Math.sqrt(range)) * 1.5);
        int cached  = 0;
        int charX   = 0;
        int maxX = 0, maxY = 0;
        int currX = 0, currY = 0;
        int currentRowMaxY = 0;

        while (cached <= range)
        {
            char c = (char) (start + cached);
            cached++;

            // 핵심: primary 폰트가 렌더 불가하면 fallback 폰트 사용
            Font useFont = selectFont(c);

            Rectangle2D bounds = useFont.getStringBounds(String.valueOf(c), frc);
            int w = Math.max(1, (int) Math.ceil(bounds.getWidth()));
            int h = Math.max(1, (int) Math.ceil(bounds.getHeight()));

            maxX = Math.max(maxX, currX + w);
            maxY = Math.max(maxY, currY + h);

            if (charX >= ceiling)
            {
                currX = 0;
                currY += currentRowMaxY + padding;
                charX = 0;
                currentRowMaxY = 0;
            }

            currentRowMaxY = Math.max(currentRowMaxY, h);
            entryData.add(new long[]{c, currX, currY, w, h});
            entryFonts.add(useFont);
            currX += w + padding;
            charX++;
        }

        // 2단계: 아틀라스 이미지 생성 & 글리프 렌더링
        int imgW = Math.max(maxX + padding, 1);
        int imgH = Math.max(maxY + padding, 1);
        BufferedImage bufferedImage = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        width  = imgW;
        height = imgH;

        Graphics2D g2d = bufferedImage.createGraphics();
        g2d.setColor(new Color(255, 255, 255, 0));
        g2d.fillRect(0, 0, imgW, imgH);
        g2d.setColor(Color.WHITE);

        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                fractionalMetrics
                        ? RenderingHints.VALUE_FRACTIONALMETRICS_ON
                        : RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                antiAlias
                        ? RenderingHints.VALUE_ANTIALIAS_ON
                        : RenderingHints.VALUE_ANTIALIAS_OFF);
        // CJK 힌팅: GASP 테이블 사용 (한글 가독성 향상)
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                antiAlias
                        ? RenderingHints.VALUE_TEXT_ANTIALIAS_GASP
                        : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        // 문자별로 적합한 폰트로 렌더링
        for (int i = 0; i < entryData.size(); i++)
        {
            long[] d    = entryData.get(i);
            char   c    = (char) d[0];
            int    texX = (int) d[1];
            int    texY = (int) d[2];
            int    w    = (int) d[3];
            int    h    = (int) d[4];
            Font   useFont = entryFonts.get(i);

            g2d.setFont(useFont);
            FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString(String.valueOf(c), texX, texY + fm.getAscent());
            glyphs.put(c, new Glyph(texX, texY, w, h, c, this));
        }

        g2d.dispose();
        registerTexture(id, bufferedImage);
        generated = true;
    }

    /**
     * 문자에 맞는 폰트를 선택한다.
     *
     * 1. primary 폰트가 표시 가능 → primary 사용
     * 2. fallbackFont가 null이 아니고 표시 가능 → fallback 사용
     * 3. 둘 다 불가 → primary 반환 (최선 시도)
     */
    private Font selectFont(char c)
    {
        if (font.canDisplay(c)) return font;
        if (fallbackFont != null && fallbackFont.canDisplay(c)) return fallbackFont;
        return font;
    }

    public Identifier getId()  { return id; }
    public int getWidth()      { return width; }
    public int getHeight()     { return height; }

    // https://github.com/0x3C50/Renderer
    public void registerTexture(Identifier identifier, BufferedImage bufferedImage)
    {
        try
        {
            int imageWidth  = bufferedImage.getWidth();
            int imageHeight = bufferedImage.getHeight();
            NativeImage image = new NativeImage(
                    NativeImage.Format.RGBA, imageWidth, imageHeight, false);
            long ptr = ((AccessorNativeImage) (Object) image).hookGetPointer();
            IntBuffer backingBuffer = MemoryUtil.memIntBuffer(ptr, imageWidth * imageHeight);

            WritableRaster raster     = bufferedImage.getRaster();
            ColorModel     colorModel = bufferedImage.getColorModel();
            int bands    = raster.getNumBands();
            int dataType = raster.getDataBuffer().getDataType();

            Object dataBuffer = switch (dataType)
            {
                case DataBuffer.TYPE_BYTE   -> new byte[bands];
                case DataBuffer.TYPE_USHORT -> new short[bands];
                case DataBuffer.TYPE_INT    -> new int[bands];
                case DataBuffer.TYPE_FLOAT  -> new float[bands];
                case DataBuffer.TYPE_DOUBLE -> new double[bands];
                default -> throw new IllegalArgumentException(
                        "Unknown data buffer type: " + dataType);
            };

            for (int y = 0; y < imageHeight; y++)
            {
                for (int x = 0; x < imageWidth; x++)
                {
                    raster.getDataElements(x, y, dataBuffer);
                    int a = colorModel.getAlpha(dataBuffer);
                    int r = colorModel.getRed(dataBuffer);
                    int g = colorModel.getGreen(dataBuffer);
                    int b = colorModel.getBlue(dataBuffer);
                    backingBuffer.put(a << 24 | b << 16 | g << 8 | r);
                }
            }

            NativeImageBackedTexture texture = new NativeImageBackedTexture(image);
            texture.upload();
            texture.setFilter(true, true);

            if (RenderSystem.isOnRenderThread())
                mc.getTextureManager().registerTexture(identifier, texture);
            else
                RenderSystem.recordRenderCall(
                        () -> mc.getTextureManager().registerTexture(identifier, texture));
        }
        catch (Throwable e)
        {
            e.printStackTrace();
        }
    }
}
