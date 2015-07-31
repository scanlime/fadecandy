package opc;

import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a client connection to one FadeCandy server. The idea is 
 * to replicate the interface provided by Adafruit's Neopixel library 
 * for the Arduino.
 * 
 * @see https://github.com/scanlime/fadecandy
 * @see https://github.com/adafruit/Adafruit_NeoPixel
 */
public class OpcClient implements AutoCloseable {

	public static final int BLACK = 0x000000;
	
	private Socket socket;
	protected OutputStream output;
	private final String host;
	private final int port;
	protected final int channel = 0;
	private byte firmwareConfig = 0;
	protected int numPixels = 0;
	private List<OpcDevice> deviceList = new ArrayList<OpcDevice>();
	protected boolean initialized = false;
	protected byte[] packetData;
	private boolean verbose = true;

	protected boolean interpolation = false;

	protected boolean dithering = true;

	/**
	 * Construct a new OPC Client.
	 * 
	 * @param hostname Host name or IP address.
	 * @param portNumber Port number
	 */
	public OpcClient(String hostname, int portNumber) {
		this.host = hostname;
		this.port = portNumber;
		this.firmwareConfig |= 0x02;
	}

	/**
	 * Add one new Fadecandy device.
	 */
	public OpcDevice addDevice() {
		int opcOffset = deviceList.size() * 512;
		int np = 0;
		for (OpcDevice device : deviceList) {
			np += device.pixelCount;
		}
		this.numPixels = np;
		OpcDevice device = new OpcDevice(this);
		device.opcOffset = opcOffset;
		deviceList.add(device);
		initialized = false;
		return device;
	}

	/**
	 * Execute all registered animations on the {@link PixelStrip} objects.
	 * 
	 * @return whether a {@code show} operation was executed.
	 */
	public boolean animate() {
		boolean redrawNeeded = false;
		for (OpcDevice device : deviceList) {
			redrawNeeded |= device.animate();
		}
		if (redrawNeeded) {
			this.show();
		}
		return redrawNeeded;
	}

	/**
	 * Reset all pixels to black.
	 */
	public void clear() {
		for (int i = 0; i < numPixels; i++) {
			this.setPixelColor(i, OpcClient.BLACK);
		}
	}

	@Override
	public void close() {
		try {
			if (this.output != null) {
				this.output.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			this.output = null;
		}

		try {
			if (this.socket != null && (!this.socket.isClosed())) {
				this.socket.close();
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			this.socket = null;
		}
	}

	/**
	 * @return A JSON string that can be used to configure the Fadecandy server.
	 */
	public String getConfig() {
		StringBuffer sb = new StringBuffer();
		sb.append("{\n");

		sb.append("\t\"listen\": [ ").append("\"").append(host).append("\", ")
				.append(port).append("],\n");
		sb.append("\t\"verbose\": ").append(verbose).append(",\n");

		sb.append("\t\"devices\": [\n");
		String sep = "";
		for (OpcDevice device : deviceList) {
			sb.append(sep);
			sb.append(device.getConfig());
			sep = ",\n";
		}
		sb.append("\t]\n");

		sb.append("}\n");
		return sb.toString();
	}

	/**
	 * Retrieve a pixel color within the global pixel map of this client.
	 * 
	 * @param number absolute number of the pixel within the server.
	 * @return color represented as an integer.
	 */
	protected int getPixelColor(int number) {
		if (!initialized) {
			init();
		}
		int offset = 4 + number * 3;
		return (packetData[offset] << 16) | (packetData[offset + 1] << 8)
				| packetData[offset + 2];
	}

	/**
	 * Reset the packet data buffer.
	 */
	protected void init() {
		if (!initialized) {
			this.numPixels = this.getMaxOpcPixel();
			int numBytes = 3 * this.numPixels;
			int packetLen = 4 + numBytes;
			packetData = new byte[packetLen];
			packetData[0] = (byte) this.channel;
			packetData[1] = 0; // Command (Set pixel colors)
			packetData[2] = (byte) (numBytes >> 8);
			packetData[3] = (byte) (numBytes & 0xFF);
		}
		initialized = true;
	}
	
	protected int getMaxOpcPixel() {
		int max = 0;
		for (OpcDevice device : deviceList)  {
			max = Math.max(max, device.getMaxOpcPixel());
		}
		return max;
	}

	/**
	 * Print a message out to the console.
	 */
	protected void log(String msg, Exception e) {
		if (! verbose) { return; }
		System.out.println(msg);
		if (e != null) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Open a socket connection to the Fadecandy server.
	 */
	protected void open() {
		if (this.output == null) {
			try {
				socket = new Socket(this.host, this.port);
				socket.setTcpNoDelay(true);
				output = socket.getOutputStream();
				sendFirmwareConfigPacket();
			} catch (Exception e) {
				log("open: error: " + e, e);
				this.close();
			}
		}
	}
	
	/**
	 * Send a control message to the Fadecandy server setting up proper
	 * interpolation and dithering.
	 */
	protected void sendFirmwareConfigPacket() {
		if (output == null) {
			log("sendFirmwareConfigPacket: no socket", null);
			return;
		}

		byte[] packet = new byte[9];
		packet[0] = (byte) this.channel; // Channel (reserved)
		packet[1] = (byte) 0xFF; // Command (System Exclusive)
		packet[2] = 0; // Length high byte
		packet[3] = 5; // Length low byte
		packet[4] = 0x00; // System ID high byte
		packet[5] = 0x01; // System ID low byte
		packet[6] = 0x00; // Command ID high byte
		packet[7] = 0x02; // Command ID low byte
		packet[8] = firmwareConfig;

		writePixels(packet);
	}

	/**
	 * Turn on/off the temporal dithering.
	 * 
	 * @param enabled whether to do temporal dithering.
	 */
	public void setDithering(boolean enabled) {
		this.dithering = enabled;
		if (enabled) {
			firmwareConfig &= ~0x01;
		} else {
			firmwareConfig |= 0x01;
		}
		sendFirmwareConfigPacket();
	}

	/**
	 * Turn on/off the inter-frame. If this is turned off, pixels will respond
	 * instantly.
	 * 
	 * @param enabled whether to interpolate.
	 */
	public void setInterpolation(boolean enabled) {
		this.interpolation = enabled;
		if (enabled) {
			firmwareConfig &= ~0x02;
		} else {
			firmwareConfig |= 0x02;
		}
		sendFirmwareConfigPacket();
	}

	/**
	 * Set a pixel color within the global pixel map of this client.
	 * 
	 * @param opcPixel number of the pixel within the server.
	 * @param color color represented as an integer.
	 */
	protected void setPixelColor(int opcPixel, int color) {
		if (!initialized) {
			init();
		}
		int offset = 4 + opcPixel * 3;
		packetData[offset] = (byte) (color >> 16);
		packetData[offset + 1] = (byte) (color >> 8);
		packetData[offset + 2] = (byte) color;
	}

	/**
	 * @param b Whether to do verbose logging.
	 */
	public void setVerbose(boolean b) {
		this.verbose = b;
	}

	/**
	 * Push all pixel changes to the strip.
	 */
	public void show() {
		if (!initialized) {
			init();
		}
		if (this.output != null) {
			this.open();
		}
		writePixels(packetData);
	}

	@Override
	public String toString() {
		return "OpcClient(" + this.host + "," + this.port + ")";
	}

	/**
	 * Push a pixel buffer out the socket to the Fadecandy.
	 */
	protected void writePixels(byte[] packetData) {
		if (packetData == null || packetData.length == 0) {
			log("writePixels: no packet data", null);
			return;
		}
		if (output == null) {
			open();
		}
		if (output == null) {
			log("writePixels: no socket", null);
			return;
		}

		try {
			output.write(packetData);
			output.flush();
		} catch (Exception e) {
			log("writePixels: error : " + e, e);
			close();
		}
	}
	
	/**
	 * Package red/green/blue values into a single integer.
	 */
	public static int makeColor(int red, int green, int blue) {
		assert red >= 0 && red <= 255;
		assert green >= 0 && green <= 255;
		assert blue >= 0 && red <= blue;
		int r = red & 0x000000FF;
		int g = green & 0x000000FF;
		int b = blue & 0x000000FF;
		return (r << 16) | (g << 8) | (b);
	}
	
	// - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - 
	// Pixel strip test
	// This is just test code.  Write your own code that sets up your pixel configuration,
	// animation combinations, and interactions.
	
	public static void main(String[] arg) throws Exception {
		String FC_SERVER_HOST = System.getProperty("fadecandy.server", "raspberrypi.local");
		int FC_SERVER_PORT = Integer.parseInt(System.getProperty("fadecandy.port", "7890"));
		int STRIP1_COUNT = Integer.parseInt(System.getProperty("fadecandy.strip1.count", "64"));
		
		OpcClient server = new OpcClient(FC_SERVER_HOST, FC_SERVER_PORT);
		OpcDevice fadeCandy = server.addDevice();
		PixelStrip strip = fadeCandy.addPixelStrip(0, STRIP1_COUNT);
		System.out.println(server.getConfig());
		int wait = 50;
		
		// Color wipe: in red, green, and blue
		for (int color : new int[]{0xFF0000, 0x00FF00, 0x0000FF}) {
			for (int i=0; i<strip.getPixelCount(); i++) {
				strip.setPixelColor(i, color);
				server.show();
				Thread.sleep(wait); 
			}
			server.clear();
			server.show();
		}
		
		// Rainbow
		for (int j=0; j<256; j++) {
			for (int i=0; i<strip.getPixelCount(); i++) {
				strip.setPixelColor(i, colorWheel(i+j));
			}
			server.show();
			Thread.sleep(wait); 
		}
		
		// Rainbow cycle
		for (int j=0; j<256*5; j++) {
			for (int i=0; i<strip.getPixelCount(); i++) {
				int c = (int)Math.round(i * 256.0 / strip.getPixelCount());
				strip.setPixelColor(i, colorWheel(c + j));
			}
			server.show();
			Thread.sleep(wait); 
		}
		
		server.clear();
		server.show();
		server.close();
	}
	
	/**
	 * Input a value 0 to 255 to get a color value.
	 * The colors are a transition r - g - b - back to r.
	 */
	private static int colorWheel(int c) {
		byte n = (byte)c;
		if (n < 85)  {
			return makeColor(n*3, 255 - n*3, 0);
		} else if (n < 170) {
			return makeColor(255 - n*3, 0, n*3);
		} else {
			return makeColor(0, n*3, 255 - n*3);
		}
	}


}
