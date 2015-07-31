package examples;

import opc.Animation;
import opc.OpcClient;
import opc.OpcDevice;
import opc.PixelStrip;

/**
 * Display a moving white pixel with trailing orange/red flames
 * This looks pretty good with a ring of pixels.
 */
public class Spark extends Animation {
	
	/** Colors of the chasing pixel. */
	int color[] = { makeColor(196, 196, 196), // White
			makeColor(128, 128, 0), // Yellow
			makeColor(96, 64, 0), // Yellow-orange
			makeColor(64, 32, 0), // orange
			makeColor(32, 0, 0), // red
			makeColor(16, 0, 0), // red
			makeColor(0, 0, 0), // black
	};

	int currentPixel;
	int numPixels;

	@Override
	public void reset(PixelStrip strip) {
		currentPixel = 0;
		numPixels = strip.getPixelCount();
	}

	@Override
	public boolean draw(PixelStrip strip) {
		for (int i = 0; i < color.length; i++) {
			strip.setPixelColor(pixNum(currentPixel, i), color[i]);
		}
		currentPixel = pixNum(currentPixel + 1, 0);
		return true;
	}

	/**
	 * Return the pixel number that is i steps behind number p.
	 */
	int pixNum(int p, int i) {
		return (p + numPixels - i) % numPixels;
	}
	
	
	
	
	public static void main(String[] args) throws Exception {
		String FC_SERVER_HOST = System.getProperty("fadecandy.server", "raspberrypi.local");
		int FC_SERVER_PORT = Integer.parseInt(System.getProperty("fadecandy.port", "7890"));
		int STRIP1_COUNT = Integer.parseInt(System.getProperty("fadecandy.strip1.count", "64"));
		
		OpcClient server = new OpcClient(FC_SERVER_HOST, FC_SERVER_PORT);
		OpcDevice fadeCandy = server.addDevice();
		PixelStrip strip1 = fadeCandy.addPixelStrip(0, STRIP1_COUNT);
		System.out.println(server.getConfig());
		
		strip1.setAnimation(new Spark());
		
		for (int i=0; i<1000; i++) {
			server.animate();
			Thread.sleep(100);
		}
		
		server.close();
	}
}
