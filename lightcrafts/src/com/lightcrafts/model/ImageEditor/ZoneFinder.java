/* Copyright (C) 2005-2011 Fabio Riccardi */
/* Copyright (C) 2018-     Masahiro Kitagawa */

package com.lightcrafts.model.ImageEditor;

import com.lightcrafts.image.color.ColorScience;
import com.lightcrafts.jai.JAIContext;
import com.lightcrafts.jai.utils.Functions;
import com.lightcrafts.model.Operation;
import com.lightcrafts.model.Preview;
import com.lightcrafts.model.Region;
import com.lightcrafts.model.ZoneOperation;
import com.lightcrafts.ui.LightZoneSkin;
import com.lightcrafts.utils.Segment;
import lombok.val;

import javax.media.jai.BorderExtender;
import javax.media.jai.JAI;
import javax.media.jai.LookupTableJAI;
import javax.media.jai.PlanarImage;
import javax.media.jai.RenderedOp;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.color.ICC_ProfileRGB;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.awt.image.renderable.ParameterBlock;

import static com.lightcrafts.model.ImageEditor.Locale.LOCALE;

public class ZoneFinder extends Preview implements PaintListener {
    private static final boolean ADJUST_GRAYSCALE = true;
    private final boolean colorMode;
    final ImageEditorEngine engine;

    @Override
    public String getName() {
        return LOCALE.get( colorMode ? "ColorZones_Name" : "Zones_Name" );
    }

    @Override
    public void setDropper(Point p) {
        if (p == null || engine == null)
            return;

        val sample = engine.getAveragedPixelValue(p.x, p.y);
        val zone = (sample != null) ? (int) Math.round(calcZone(sample)) : -1;
        setFocusedZone(zone);
        // repaint();
    }

    @Override
    public void addNotify() {
        // This method gets called when this Preview is added.
        engine.update(null, false);
        super.addNotify();
    }

    @Override
    public void removeNotify() {
        // This method gets called when this Preview is removed.
        super.removeNotify();
    }

    @Override
    public void setRegion(Region region) {
        // Fabio: only draw yellow inside the region?
    }

    @Override
    public void setSelected(Boolean selected) {
        if (!selected)
            zones = null;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (zones == null)
            engine.update(null, false);

        // Fill in the background:
        Graphics2D g = (Graphics2D) graphics;
        Shape clip = g.getClip();
        g.setColor(LightZoneSkin.Colors.NeutralGray);
        g.fill(clip);

        if (zones != null) {
            int dx, dy;
            AffineTransform transform = new AffineTransform();
            if (getSize().width > zones.getWidth())
                dx = (getSize().width - zones.getWidth()) / 2;
            else
                dx = 0;
            if (getSize().height > zones.getHeight())
                dy = (getSize().height - zones.getHeight()) / 2;
            else
                dy = 0;
            transform.setToTranslation(dx, dy);
            try {
                g.drawRenderedImage(zones, transform);
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    private int currentFocusZone = -1;

    private BufferedImage lastPreview = null;

    void setFocusedZone(int index) {
        currentFocusZone = index;

        if (!colorMode && ADJUST_GRAYSCALE && lastPreview != null) {
            zones = requantize(lastPreview, currentFocusZone);
            repaint();
        }
    }

    private RenderedImage zones;

    ZoneFinder(ImageEditorEngine engine) {
        this(engine, false);
    }

    ZoneFinder(final ImageEditorEngine engine, boolean colorMode) {
        this.engine = engine;
        this.colorMode = colorMode;

        addComponentListener(
            new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    if (isShowing()) {
                        engine.update(null, false);
                    }
                }
            }
        );
    }

    private RenderedImage cropScaleGrayscale(Rectangle visibleRect, RenderedImage image) {
        Rectangle bounds = new Rectangle(image.getMinX(), image.getMinY(), image.getWidth(), image.getHeight());

        visibleRect = bounds.intersection(visibleRect);

        if (bounds.contains(visibleRect)) {
            ParameterBlock pb = new ParameterBlock();
            pb.addSource(image);
            pb.add((float) visibleRect.x);
            pb.add((float) visibleRect.y);
            pb.add((float) visibleRect.width);
            pb.add((float) visibleRect.height);
            image = JAI.create("Crop", pb, JAIContext.noCacheHint);
        }

        Dimension previewSize = getSize();

        if (visibleRect.width > previewSize.width || visibleRect.height > previewSize.height) {
            final float scale = Math.min(previewSize.width / (float) visibleRect.width, previewSize.height / (float) visibleRect.height);

            ParameterBlock pb = new ParameterBlock();
            pb.addSource(image);
            pb.add(scale);
            pb.add(scale);
            image = JAI.create("Scale", pb, JAIContext.noCacheHint);
        }

        // avoid keeping references to the input image
        if (image instanceof RenderedOp) {
            RenderedOp ropImage = (RenderedOp) image;

            SampleModel sm = ropImage.getSampleModel().createCompatibleSampleModel(image.getWidth(), image.getHeight());

            WritableRaster wr = Raster.createWritableRaster(sm, new Point(ropImage.getMinX(), ropImage.getMinY()));
            ropImage.copyData(wr);
            image = new BufferedImage(ropImage.getColorModel(), wr.createWritableTranslatedChild(0, 0), false, null);
            ropImage.dispose();
        }

        /* image = Functions.toColorSpace(image, JAIContext.sRGBColorSpace, null);

        if (((PlanarImage) image).getSampleModel().getDataType() == DataBuffer.TYPE_USHORT)
            image = Functions.fromUShortToByte(image, null); */

        if (!colorMode && image.getColorModel().getNumColorComponents() == 3) {
            ICC_Profile profile = ((ICC_ColorSpace) (image.getColorModel().getColorSpace())).getProfile();

            if (!(profile instanceof ICC_ProfileRGB)) {
                image = Functions.toColorSpace(image, JAIContext.sRGBColorSpace, null);
                profile = ((ICC_ColorSpace) (image.getColorModel().getColorSpace())).getProfile();
            }

            ICC_ProfileRGB rgb_profile = (ICC_ProfileRGB) profile;

            ColorScience.ICC_ProfileParameters pp = new ColorScience.ICC_ProfileParameters(rgb_profile);

            double[][] transform = {
                {pp.W[0], pp.W[1], pp.W[2], 0}
            };

            ParameterBlock pb = new ParameterBlock();
            pb.addSource(image);
            pb.add(transform);
            image = JAI.create("BandCombine", pb, JAIContext.noCacheHint); // Desaturate, single banded
        }

        return image;
    }

    static private final int steps = 16;

    /**
     * the same lightness scale used in the zone mapper
     */
    static private final int[] colors = new int[steps + 1];
    static {
        for (int i = 0; i < steps; i++) {
            val color = (float) ((Math.pow(2, i * 8.0 / (steps - 1)) - 1) / 255.);
            val srgbColor = Functions.fromLinearToCS(JAIContext.systemColorSpace, new float[] {color, color, color});
            colors[i] = (int) (255 * srgbColor[0]);
        }
        colors[steps] = colors[steps - 1];
    }

    private static int zoneFrom(int lightness) {
        for (int i = 1; i <= steps; i++) {
            if (lightness < colors[i]) {
                return i - 1;
            }
        }
        return steps;
    }

    // requantize the segmented image to match the same lightness scale used in the zone mapper
    private static RenderedImage requantize(RenderedImage image, int focusZone) {
        byte[][] lut = new byte[3][256];
        int step = 0;
        for (int i = 0; i < colors[steps]; i++) {
            if (i > colors[step])
                step++;
            if (i < (colors[step] + colors[step + 1]) / 2) {
                if (focusZone >= 0 && step ==  focusZone) {
                    lut[0][i] = (byte) Color.yellow.getRed();
                    lut[1][i] = (byte) Color.yellow.getGreen();
                    lut[2][i] = (byte) Color.yellow.getBlue();
                } else
                    lut[0][i] = lut[1][i] = lut[2][i] = (byte) (colors[step] & 0xFF);
            } else
                lut[0][i] = lut[1][i] = lut[2][i] = (byte) (colors[step + 1] & 0xFF);
        }
        for (int i = colors[steps]; i < 256; i++) {
            lut[0][i] = lut[1][i] = lut[2][i] = (byte) colors[steps];
        }

        ParameterBlock pb = new ParameterBlock();
        pb.addSource(image);
        pb.add(new LookupTableJAI(lut));

        return JAI.create("lookup", pb, JAIContext.noCacheHint);
    }

    private RenderedImage segment_bah(RenderedImage image) {
        image = Functions.fromByteToUShort(image, null);

        RenderingHints hints = new RenderingHints(JAI.KEY_BORDER_EXTENDER,
                                                  BorderExtender.createInstance(BorderExtender.BORDER_COPY));
        ParameterBlock pb = new ParameterBlock();
        pb.addSource(image);
        pb.add(4f);
        pb.add(20f);
        RenderedOp filtered = JAI.create("BilateralFilter", pb, hints);

        filtered = Functions.fromUShortToByte(filtered, null);

        RenderedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        filtered.copyData(((BufferedImage) result).getRaster());
        lastPreview = (BufferedImage) result;

        if (!colorMode && ADJUST_GRAYSCALE)
            result = requantize(result, currentFocusZone);

        return result;
    }

    private RenderedImage segment(RenderedImage image) {
        Rectangle bounds = new Rectangle(image.getMinX(), image.getMinY(), image.getWidth(), image.getHeight());

        byte[] pixels = ((DataBufferByte) image.getData(bounds).getDataBuffer()).getData();
        if (pixels.length != bounds.height * bounds.width * image.getSampleModel().getNumBands()) {
            pixels = (byte[]) image.getData(bounds).getDataElements(bounds.x, bounds.y, bounds.width, bounds.height, null);
        }

        if (pixels.length <= 0 || bounds.height <= 15 || bounds.width <= 15)
            return null;

        pixels = Segment.segmentImage(pixels, colorMode ? 3 : 1, bounds.height, bounds.width);

        DataBufferByte data = new DataBufferByte(pixels, pixels.length);

        WritableRaster raster;
        ColorModel colorModel;
        if (colorMode) {
            colorModel = image.getColorModel();
            raster = Raster.createInterleavedRaster(data, bounds.width, bounds.height, 3 * bounds.width, 3, new int[]{0, 1, 2}, null);
        } else {
            raster = Raster.createInterleavedRaster(data, bounds.width, bounds.height, bounds.width, 1, new int[]{0}, null);
            ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            colorModel = new ComponentColorModel(cs, new int[]{8}, false, true,
                                                 Transparency.OPAQUE,
                                                 DataBuffer.TYPE_BYTE);
        }

        RenderedImage result = lastPreview = new BufferedImage(colorModel, raster, false, null);

        // requantize the segmented image to match the same lightness scale used in the zone mapper
        if (!colorMode && ADJUST_GRAYSCALE)
            result = requantize(result, currentFocusZone);

        return result;
    }

    // TODO: this is ugly code, use a real queue
    class Segmenter extends Thread {
        RenderedImage image;
        RenderedImage nextImage = null;

        Segmenter(Rectangle visibleRect, PlanarImage image) {
            super("ZoneFinder Histogrammer");
            this.image = cropScaleGrayscale(visibleRect, image);
        }

        synchronized void nextView(Rectangle visibleRect, PlanarImage image) {
            nextImage = cropScaleGrayscale(visibleRect, image);
        }

        synchronized private boolean getNextView() {
            if (nextImage != null) {
                image = nextImage;
                nextImage = null;
                return true;
            } else
                return false;
        }

        @Override
        public void run() {
            do {
                if (getSize().width > 0 && getSize().height > 0) {
                    RenderedImage newZones = segment(image);
                    if (newZones != null) {
                        zones = newZones;
                        repaint();
                    }
                }
            } while (getNextView());
        }
    }

    private Segmenter segmenter = null;

    /*
        BIG NOTE: JAI has all sorts of deadlocks in its notification management,
        we just avoid doing any pipeline setup off the main event thread.
        This code sets the pipeline on the main thread but performs the actual computation on a worker thread
    */

    @Override
    public void paintDone(PlanarImage image, Rectangle visibleRect, boolean synchronous, long time) {
        Dimension previewDimension = getSize();

        assert (image.getColorModel().getColorSpace().isCS_sRGB()
                || image.getColorModel().getColorSpace() == JAIContext.systemColorSpace)
                && image.getSampleModel().getDataType() == DataBuffer.TYPE_BYTE;

        if (previewDimension.getHeight() > 1 && previewDimension.getWidth() > 1) {
            Operation op = engine.getSelectedOperation();
            if (op != null && op instanceof ZoneOperation /* && op.isActive() */ ) {
                PlanarImage processedImage = engine.getRendering(engine.getSelectedOperationIndex() + 1);
                image = Functions.fromUShortToByte(Functions.toColorSpace(processedImage,
                                                                          JAIContext.systemColorSpace,
                                                                          engine.getProofProfile(),
                                                                          null,
                                                                          engine.getProofIntent(),
                                                                          null),
                                                   null);

                if (image.getSampleModel().getDataType() == DataBuffer.TYPE_USHORT)
                    image = Functions.fromUShortToByte(image, null);
            }

            if (segmenter == null || !segmenter.isAlive()) {
                segmenter = new Segmenter(visibleRect, image);
                segmenter.start();
            } else
                segmenter.nextView(visibleRect, image);
        }
    }
}
