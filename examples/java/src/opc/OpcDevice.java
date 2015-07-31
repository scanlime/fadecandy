package opc;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents one FadeCandy device. The FadeCandy may have up to 8
 * {@link PixelStrip} objects added to it, where each strip may have up to 64
 * pixels.
 */
public class OpcDevice {

	protected final OpcClient opcClient;
	protected int pixelCount = 0;
	protected int opcOffset = 0;
	@SuppressWarnings("unchecked")
	private List<PixelStrip> stripList[] = (List<PixelStrip>[])(new List[8]);

	protected OpcDevice(OpcClient opc) {
		assert opc != null;
		this.opcClient = opc;
		this.opcOffset = 0;
		for (int i=0; i<stripList.length; i++) {
			stripList[i] = new ArrayList<PixelStrip>();
		}
	}
	
	public PixelStrip addPixelStrip(int pinNumber, int pixelCount) {
		return this.addPixelStrip(pinNumber, pixelCount, null);
	}

	public PixelStrip addPixelStrip(int pinNumber, int pixelCount, String description) {
		assert pixelCount > 0 && pixelCount <= 64;
		assert pinNumber>=0 && pinNumber < 8;
		int pixelsOnPin = 0;
		for (PixelStrip strip : stripList[pinNumber]) {
			pixelsOnPin += strip.getPixelCount();
		}
		PixelStrip strip = new PixelStrip(this, pinNumber, pixelCount, pixelsOnPin, description);
		stripList[pinNumber].add(strip);
		this.pixelCount += pixelCount;
		assert this.pixelCount <= 512;
		opcClient.initialized = false;
		return strip;
	}

	/**
	 * Execute all registered animations on the {@link PixelStrip} objects.
	 * 
	 * @return whether a {@code show} operation will be needed.
	 */
	protected boolean animate() {
		boolean redrawNeeded = false;
		for (int i=0; i<stripList.length; i++) {
			for (PixelStrip strip : stripList[i]) {
				redrawNeeded |= strip.animate();
			}
		}
		return redrawNeeded;
	}
	
	/**
	 * @return a JSON fragment representing this Fadecandy device.
	 */
	protected String getConfig() {
		StringBuilder sb = new StringBuilder();
		sb.append("\t\t{\n");
		sb.append("\t\t\t\"type\": \"fadecandy\",\n");
		sb.append("\t\t\t\"dither\": ").append(this.opcClient.dithering)
				.append(",\n");
		sb.append("\t\t\t\"interpolate\": ")
				.append(this.opcClient.interpolation).append(",\n");
		sb.append("\t\t\t\"map\": [\n");
		String sep = "\n";
		for (int i=0; i<stripList.length; i++) {
			for (PixelStrip strip : stripList[i]) {
				sb.append(sep);
				sb.append("\t\t\t\t\t").append(strip.getConfig());
				sep = ",\n";
			}
		}
		sb.append("\n\t\t\t\t]\n");
		sb.append("\t\t}");
		return sb.toString();
	}
	
	/**
	 * Set a pixel color within the global pixel map of this device.
	 * 
	 * @param opcPixel number of the pixel within this Fadecandy.
	 * @param color color represented as an integer.
	 */
	protected void setPixelColor(int opcPixel, int color) {
		this.opcClient.setPixelColor(opcPixel + opcOffset, color);
	}
	
	protected int getMaxOpcPixel() {
		int max = 0;
		for (int i = 0; i < stripList.length; i++) {
			for (PixelStrip strip : stripList[i]) {
				max = Math.max(max, strip.getMaxOpcPixel());
			}
		}
		return max;
	}

	@Override
	public String toString() {
		return "OpcDevice(" + this.pixelCount + ")";
	}

}
