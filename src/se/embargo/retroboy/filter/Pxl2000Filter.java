package se.embargo.retroboy.filter;

import java.util.Arrays;

import se.embargo.core.concurrent.IForBody;
import se.embargo.core.concurrent.Parallel;
import se.embargo.retroboy.color.IIndexedPalette;
import se.embargo.retroboy.color.IPalette;
import se.embargo.retroboy.color.MonochromePalette;

/**
 * @link	http://fox-gieg.com/tutorials/2008/fake-pxl2000-effect/
 */
public class Pxl2000Filter extends AbstractFilter {
	private final double _bordersize = 0.125d;
	private final FilterBody _body = new FilterBody();
	private final IIndexedPalette _palette = new MonochromePalette(7);
	
	/**
	 * @link	http://www.swageroo.com/wordpress/how-to-program-a-gaussian-blur-without-using-3rd-party-libraries/
	 */
	private final float[] _blurkernel = {
		0.0947416f, 0.118317f, 0.0947416f,
		0.1183180f, 0.147761f, 0.1183180f,
		0.0947416f, 0.118317f, 0.0947416f};
	
	/**
	 * Amount of sharpening
	 */
	private final float _sharpenAmount = 0.7f;
	
	/**
	 * Number of levels of color depth
	 */
	private final float _posterizeLevels = 90;
	
	/**
	 * Ratio of dynamic range compression
	 * @link	http://www.finerimage.com.au/Articles/Photoshop/Levels.php
	 */
	private final float _dynamicRangeCompression = 1.2f;
	
	/**
	 * Scratch buffer to hold result from previous frame.
	 */
	private int[] _scratch = null;
	
	/**
	 * Sequence number of processed frames
	 */
	private long _seqno = 0;

	@Override
	public IPalette getPalette() {
		return _palette;
	}
	
	@Override
	public boolean isColorFilter() {
		return false;
	}

	@Override
	public synchronized void accept(ImageBuffer buffer) {
		final int borderwidth = (int)((double)buffer.imagewidth * _bordersize);
		final int bordercolor = 0xff000000;
		

		// Initialize the scratch buffer
		if (_scratch == null || _scratch.length != buffer.image.array().length) {
			_scratch = Arrays.copyOf(buffer.image.array(), buffer.image.array().length);
		}
		
		// Apply the PXL-2000 effect
		Parallel.forRange(_body, buffer, borderwidth, buffer.imageheight - borderwidth);

		// Apply the scratch buffer
		buffer.image.rewind();
		buffer.image.put(_scratch);

		// Black out the first and last lines
		final int[] image = buffer.image.array();
		Arrays.fill(image, 0, buffer.imagewidth * borderwidth, bordercolor);
		Arrays.fill(image, buffer.imagewidth * (buffer.imageheight - borderwidth), buffer.imagewidth * buffer.imageheight, bordercolor);
		
		// Black out the sides
		for (int i = borderwidth, last = buffer.imageheight - borderwidth, pos; i < last; i++) {
			pos = buffer.imagewidth * i;
			Arrays.fill(image, pos, pos + borderwidth, bordercolor);
			
			pos = pos + buffer.imagewidth - borderwidth;
			Arrays.fill(image, pos, pos + borderwidth, bordercolor);
		}
		
		// Increment the frame sequence number
		_seqno++;
	}
	
	private class FilterBody implements IForBody<ImageBuffer> {
		@Override
		public void run(ImageBuffer buffer, int it, int last) {
			final int[] source = buffer.image.array();
			final int[] target = _scratch;
			
			final int borderwidth = (int)((double)buffer.imagewidth * _bordersize);
			final int width = buffer.imagewidth, xlast = width - borderwidth;
			final float[] kernel = _blurkernel;
			final float sharpen = _sharpenAmount;
			final float posterize = (255f / _posterizeLevels);
			final float compression = _dynamicRangeCompression;
			
			for (int y = it; y < last; y++) {
				final int yi = y * width;
				
				// Process every other pixel for each line. Flip the start position for every frame to create an 
				// interleaving of frames, keeping half of the pixels from the previous frame intact. This creates
				// a temporal dithering and lag effect.
				final int offset = (int)((_seqno + y) % 2);
				
				for (int x = borderwidth + offset; x < xlast; x += 2) {
					final int i = x + yi;
					final int pixel = source[i];
					float lum = 0, origlum = pixel & 0xff;

					// Apply Gaussian blur
					lum += (float)(source[i - width - 1] & 0xff) * kernel[0];
					lum += (float)(target[i - width    ] & 0xff) * kernel[1];
					lum += (float)(source[i - width + 1] & 0xff) * kernel[2];
					lum += (float)(target[i		    - 1] & 0xff) * kernel[3];
					lum += (float)(source[i			   ] & 0xff) * kernel[4];
					lum += (float)(target[i		    + 1] & 0xff) * kernel[5];
					lum += (float)(source[i + width - 1] & 0xff) * kernel[6];
					lum += (float)(target[i + width	   ] & 0xff) * kernel[7];
					lum += (float)(source[i + width + 1] & 0xff) * kernel[8];
					
					// Apply unsharp mask
					final float contrast = Math.abs(origlum - lum) * sharpen;
					final float factor = (259f * ((float)contrast + 255f)) / (255f * (259f - (float)contrast));
					lum = factor * (lum - 128f) + 128f;
					
					// Clamp to 5% and 95% light levels
					lum = Math.max(12.75f, Math.min(lum, 242.25f));
					
					// Compress dynamic range
					//lum = (lum - 128f) * compression + 128f;
					lum = lum * compression;
					
					// Posterize to reduce color depth
					lum = Math.round(lum / posterize) * posterize;

					// Clamp to 5% and 95% light levels
					lum = Math.max(12.75f, Math.min(lum, 242.25f));
					
					// Output the pixel, but keep alpha channel intact
					final int color = (int)lum;
					target[i] = (pixel & 0xff000000) | (color << 16) | (color << 8) | color;
				}
			}
		}
	}
}