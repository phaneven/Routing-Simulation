import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


/*
 * every node need to broadcast itself and keep receive link_state_packet
 * then broadcast
 */



public class Lsr {
	
	protected static final int ROUTE_UPDATE_INTERVAL = 30000;
	public volatile ArrayList<Node> S = new ArrayList<Node>();
	Map<Character, Integer> countMap = new ConcurrentHashMap<>(); // count its neighbors valid or not
	Map<Character, ArrayList<Character>> recordMap = new ConcurrentHashMap<Character, ArrayList<Character>>(); // record every node send to this router
	public volatile boolean SenderFlag = true; // control thread 
	public volatile boolean ListenerFlag = true; // control thread
	static Timer timer;
	private String info = "";
	private Node node = null;
	public static Lsr lsr = null;
	
	public static void main(String args[]) throws Exception  {
		/* all arguments */
		final char NODE_ID = args[0].charAt(0);
		final int NODE_PORT = Integer.parseInt(args[1]); // port name
		final String CONFIG_File_Path = args[2];
		
		final InetAddress IPAddress = InetAddress.getLocalHost(); 
		
		lsr = new Lsr();
		try {
			InputStreamReader reader = new InputStreamReader (new FileInputStream(CONFIG_File_Path));
			BufferedReader br = new BufferedReader(reader);
			String line = "";
			line = br.readLine();
			lsr.info += line; 
			lsr.info += " ";
			while (line != null) {
				line = br.readLine();
				if (line != null) { // update cost map and port map
					lsr.info += line;
					lsr.info += " ";
				}
			}
			br.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
//		System.out.println(lsr.info);
		
		// set a router(node)
		lsr.node = new Node(NODE_ID,NODE_PORT, lsr.info);
		lsr.node.getNeighbours();
		
		/************************ listener thread *********************/
		Thread Listener = new Thread(new Runnable() {
			final ReadWriteLock lock = new ReentrantReadWriteLock();
			DatagramSocket receiveSocket = new DatagramSocket(NODE_PORT);
			private int ComingOrigPort = 0;
			private char ComingOrigID = 0;
			private String ComingInfo = "";
			
			private int ComingPort = 0;
			private char ComingID = 0;
			
			  
			
			public void fetchComingOrigPort(byte[] buf) {
				ComingOrigPort = 0;
				ComingOrigPort += (buf[11] & 0xff);
				ComingOrigPort += (buf[10] & 0xff) << 8;
				ComingOrigPort += (buf[9] & 0xff) << 16;
				ComingOrigPort += (buf[8] & 0xff) << 24;
			}
			
			public void fetchComingOrigID(byte[] buf) {
				ComingOrigID = (char) buf[12];
			}
			
			public void fetchComingInfo(byte[] buf) {
//				System.out.println("buf: " + new String(buf));
				byte[] temp = new byte[1024];
				synchronized(this) {
					System.arraycopy(buf, 13, temp, 0, buf.length-13);
				}
				ComingInfo = new String(temp);
//				System.out.println ("fetch: " + ComingInfo);
			}
			// fetch which neighbor send me the package
			public void fetchComing(byte[] buf) {
				ComingPort = 0;
				ComingPort += (buf[3] & 0xff);
				ComingPort += (buf[2] & 0xff) << 8;
				ComingPort += (buf[1] & 0xff) << 16;
				ComingPort += (buf[0] & 0xff) << 24;
				for (char k : lsr.node.portMap.keySet()) {
					if (lsr.node.portMap.get(k) == ComingPort) {
						this.ComingID = k;
						break;
					}
				}
			}
			
			
			@Override
			public void run() {
				// TODO Auto-generated method stub
				while (lsr.ListenerFlag) {
					byte[] buf = new byte[1024];
					DatagramPacket Packet = new DatagramPacket(buf, buf.length);
					try {
						receiveSocket.receive(Packet);
						// step 1
						// which neighbor send me this packet at the very beginning
						this.fetchComingOrigID(buf);
						this.fetchComingOrigPort(buf);
						this.fetchComingInfo(buf);
						// we create node to mark this node has already transmit to this router
						Node ComingNode = new Node(this.ComingOrigID, this.ComingOrigPort, this.ComingInfo);
						ComingNode.getNeighbours();
						
						// add it in a global set S
						boolean check = true;
						for (Node n	: lsr.S) { 
							if (n.getID() == ComingNode.getID()) {
								check = false;
							}
						}
						if (check) {lsr.S.add(ComingNode);}
						
						// step 2
						fetchComing(buf);
						if (lsr.countMap.containsKey(ComingID)) {
							lsr.countMap.put(ComingID, lsr.countMap.get(ComingID) + 1);
						} else {
							lsr.countMap.put(ComingID, 1);
						}
						
						// we assume at least receive 3 packets indicating that the router is existed
						DatagramSocket clientSocket;
						for (char key : lsr.countMap.keySet()) {
							// guarantee not duplicate send
							if (key != ComingID && lsr.countMap.get(key) > 3  && !lsr.recordMap.get(key).contains(ComingOrigID) ) {
								try {
									LSP lsp = new LSP(lsr.node.getPort(), lsr.node.portMap.get(key), ComingNode);
									byte[] nbuf = lsp.createPacket();
									clientSocket = new DatagramSocket();
									
//									System.out.println(new String(nbuf));
									DatagramPacket sendPacket = new DatagramPacket(nbuf, nbuf.length, IPAddress, lsr.node.portMap.get(key));  // create datagram with data-to-send, length, IP, port
									clientSocket.send(sendPacket); 
									clientSocket.close();
						
								} catch (SocketException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}  
								
							}
						}
						
						// after send we record it
						if (lsr.recordMap.containsKey(this.ComingID)) {
							boolean exist = false;
							for (char c : lsr.recordMap.get(ComingID)) {
								if (c == this.ComingOrigID) {
									exist = true;
									break;
								}
							}
							if (!exist) {
								ArrayList<Character> temp = lsr.recordMap.get(ComingID);
								temp.add(ComingOrigID);
								lsr.recordMap.put(ComingID, temp);
							} 
						} else {
							ArrayList<Character> temp = new ArrayList<>();
							temp.add(ComingOrigID);
							lsr.recordMap.put(ComingID, temp);
						}
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			
		});
		
		/********************** Sender thread *******************/
		Thread Sender = new Thread(new Runnable() {
			@Override
			public void run() {
				// TODO Auto-generated method stub
				while(lsr.SenderFlag) {
					long UPDATE_INTERVAL = 1000;
					for (int destination : lsr.node.portMap.values()) {
						DatagramSocket clientSocket;
						try {
							clientSocket = new DatagramSocket(); // create socket
							LSP lsp = new LSP(lsr.node.getPort(), destination, lsr.node);
							byte[] buf = lsp.createPacket();
//							System.out.println("send: " + new String(buf));
							DatagramPacket sendPacket = new DatagramPacket(buf, buf.length, IPAddress, destination);  // create datagram with data-to-send, length, IP, port
							clientSocket.send(sendPacket);
//							System.out.println ("send: " + node.getInfo());
							clientSocket.close(); 
						} catch (SocketException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}  
					}
//					System.out.println(".........");
					try {
						Thread.sleep(UPDATE_INTERVAL); // sleep a period of time
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			
		});
		// timer Task for every 30s to calculate
		TimerTask timerTask = new TimerTask() {
			@Override
			public void run() {
				// TODO Auto-generated method stub
				System.out.println ("Calculate shortest paths ...");
				lsr.ListenerFlag = false;
				lsr.SenderFlag = false;
				ArrayList<Node> Scopy = lsr.S;
				for (char k : lsr.countMap.keySet()) {
//					System.out.println("time: "+lsr.countMap.get(k));
					if (lsr.countMap.get(k) < 3) { // if less than 3 times, we assume the router is closed
						for (Node n : lsr.S) {
							if (n.getID() == k) {
								Scopy.remove(n);
								break;
							}
						}
					}
				}
				// recall Dijkstra's algorithm
				lsr.S = Scopy;
				Dijkstra(lsr.node, Scopy);
				lsr.countMap.clear();
				lsr.S.clear();
				lsr.recordMap.clear();
				lsr.ListenerFlag = true;
				lsr.SenderFlag = true;
			}
			
		};
		
		timer = new Timer("MyTimer");
		timer.schedule(timerTask, ROUTE_UPDATE_INTERVAL, ROUTE_UPDATE_INTERVAL);
		Listener.start();
		Sender.start();
	
	}
	
	// Dijkstra Algorithm
	public static void Dijkstra(Node node, ArrayList<Node> S) {
		ArrayList<Node> U = new ArrayList<Node>();
		int[] mark = new int[U.size()];
		U.add(node);
		
		float cost = 0f;
		float[] D = new float[9]; // maximum 10 nodes, one node is in U, others in V 
		char[] prev = new char[9];
		// initialize	
		char k = 0;
		float v = 0;
		Node currentNode = U.get(U.size()-1);	
		for (int index = 0; index < S.size(); index++) {
			if (isNeighbour(currentNode, S.get(index))) {
				k = S.get(index).getID();
				v = currentNode.costMap.get(k);
				D[index] = v;
				prev[index] = currentNode.getID();
			}
			else {D[index] = Float.MAX_VALUE;}
		}
		// LOOP
		while(U.size() != S.size() + 1) {
			int index = findMinimum(D, U, S);
			Node wNode = S.get(index);
			U.add(wNode);  // add to set U
			boolean ExistInU = false;
			for (char wk : wNode.costMap.keySet()) { // all neighbours of wNode, except those have already in U
				ExistInU = false;
				for (Node n : U) { // check wNode's neighbour in U or not
					if (n.getID() == wk) {
						ExistInU = true;
						break;
					}
				}
				if (!ExistInU) {
					int windex = getIndexFromID(S, wk);
					float cc = wNode.costMap.get(wk); // cost of wNode to its neighbour
					if (D[windex] > D[index] + cc) {
						D[windex] = D[index] + cc;
						prev[windex] = wNode.getID();
					}
				}
			}
		}
		// print result
		for (int i = 0; i < S.size(); ++i) {
			String path = "";
			path += S.get(i).getID();
			char c = 0;
			int ii = i;
			while (c != node.getID()) {
				c = prev[ii];
				path += c;
				ii = getIndexFromID (S, prev[ii]);
			}
			path = new StringBuilder(path).reverse().toString();
			System.out.println("least-cost path to node "+ S.get(i).getID() + ": " + path + " and the cost is " + D[i]);
		}
	
	}
	
	public static boolean isNeighbour(Node n1, Node n2) {
		for (char key : n1.costMap.keySet()) {
			if (key == n2.getID()) {
				return true;
			}
		}
		return false;
	}
	
	public static int findMinimum(float[] D, ArrayList<Node> U, ArrayList<Node> S) {
		float t = Float.MAX_VALUE;
		int r = 0;
		for (int i = 0; i < S.size(); i++) {
			boolean ExistInU = false;
			for (Node n : U) {  // the node is not in the set U
				if (S.get(i).getID() == n.getID()) {
					ExistInU = true;
					break;
				}
			}
			if (D[i] < t && !ExistInU) { // find the minimum value's position
				t = D[i];
				r = i;
			}
		}
		return r;
	}
	
	public static int getIndexFromID (ArrayList<Node> S, char ID) {
		int index = 0;
		for (int i = 0; i < S.size(); i++) { // find ID corresponding index
			if (S.get(i).getID() == ID) {
				index = i;
				return index;
			}
		}
		return S.size(); //  this will not happen
	}

}




/*
 * 	Node = Router
 */

class Node {
	private char ID = 0; // router ID
	private int port = 0; // router corresponding port
	private int neighbourNum = 0;
	private String info = ""; // store the router's neighbor information
	
	Map<Character, Float> costMap = new ConcurrentHashMap<Character, Float>();  // store neighbors' cost table
	Map<Character, Integer> portMap = new ConcurrentHashMap<Character, Integer>(); // store neighbors' port table
	
	Node (char ID, int port, String info) {
		this.ID = ID;
		this.port = port;
		this.info = info;
	}
	
	public char getID() {
		return this.ID;
	}
	public int getPort() {
		return this.port;
	}
	public int getNeighbourNum () {
		return this.neighbourNum;
	}
	public String getInfo () {
		return this.info;
	}
	public void getNeighbours() {
		String[] infoData = info.split(" ");
		this.neighbourNum = Integer.parseInt(infoData[0]);
		for (int i = 1; i <= infoData.length-3; i+=3) {
			this.costMap.put(infoData[i].charAt(0), Float.parseFloat(infoData[i+1]));
			this.portMap.put(infoData[i].charAt(0), Integer.parseInt(infoData[i+2]));		
		}
	}
}

/*
 *  link_state_packet
 */

class LSP {
	private int SourcePort = 0; // used for transmit
	private int DestinationPort = 0;
	
	private String info = "";
	private int packetSize = 0;
	private char origID = 0;
	private int origPort = 0;
	
	LSP (int SourcePort, int DestinationPort, Node node) {
		this.SourcePort = SourcePort;
		this.DestinationPort = DestinationPort;
		this.origID = node.getID();
		this.origPort = node.getPort();
		this.info = node.getInfo();
	}
	// header stores transmit information
	public byte[] createHeader() { 
		byte[] header = new byte[13]; // 13 bit
		// source port
		header[0] = (byte)((SourcePort >> 24) & 0xFF);
		header[1] = (byte)((SourcePort >> 16) & 0xFF);
		header[2] = (byte)((SourcePort >> 8) & 0xFF);
		header[3] = (byte)((SourcePort) & 0xFF);
		// destination port
		header[4] = (byte)((DestinationPort >> 24) & 0xFF);
		header[5] = (byte)((DestinationPort >> 16) & 0xFF);
		header[6] = (byte)((DestinationPort >> 8) & 0xFF);
		header[7] = (byte)((DestinationPort) & 0xFF);
		// orig port
		header[8] = (byte)((origPort >> 24) & 0xFF);
		header[9] = (byte)((origPort >> 16) & 0xFF);
		header[10] = (byte)((origPort >> 8) & 0xFF);
		header[11] = (byte)((origPort) & 0xFF);
		// orig ID
		header[12] = (byte)(origID & 0xFF); 
		return header;
	}
	// neighbors information
	public byte[] createData() {
		byte[] data = info.getBytes();
		this.packetSize = 13 + data.length;
		return data;
	}
	public byte[] createPacket() {
		byte[] d = createData();
		byte[] h = createHeader();
		byte[] P = new byte[packetSize];
		System.arraycopy(h, 0, P, 0, h.length);
		System.arraycopy(d, 0, P, h.length, d.length);
		return P;
	}
}
