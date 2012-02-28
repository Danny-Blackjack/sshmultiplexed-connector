package org.mule.modules.ssh.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;
import org.mule.modules.ssh.multiplexer.exception.CommunicationException;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;

/**
 * This class represents a client connected to a remote host.
 * 
 * It basically holds a Session.
 * per each command
 * 
 * @author marianogonzalez
 *
 */
public class SshClient {

	private static final Logger logger = Logger.getLogger(SshClient.class);
	
	private SshConnectionDetails details = null;
	
	private Connection connection = null;
	private Session session = null;
	
	private SshThread responseConsumerThread;
	private SshThread responseProducerThread;
	
	private BlockingQueue<String> receiverBuffer = new LinkedBlockingQueue<String>();
	private int receiverBufferSize;
	
	private OutputStream sender = null;
	private InputStream receiver = null;

	public SshClient(SshConnectionDetails details) {
		this.details = details;
		this.receiverBufferSize = details.getReceiverBufferSize();
	}
	
	public void connect() throws IOException {
		if (this.connection == null) {
			
			Connection connection = new Connection(this.details.getHost());
			connection.connect();
			boolean isAuthenticated = connection.authenticateWithPassword(this.details.getUsername(), this.details.getPassword());

			if (isAuthenticated) {
				this.connection = connection;
			} else {
				throw new IOException("Authentication failed.");
			}

			Session session = this.connection.openSession();
			
			if (this.details.isShellMode()) {
				session.requestPTY("dumb", 500, 500, 0, 0, null);
				session.startShell();
			}
			
			this.session = session;
			this.sender = session.getStdin();
			this.receiver = session.getStdout();
			
			this.responseConsumerThread = new ResponseConsumerThread(this);
			this.responseProducerThread = new ResponseProducerThread(this);
			
			this.responseConsumerThread.start();
			this.responseProducerThread.start();
		}
	}

	/**
	 * indicates if the connection is active
	 * @return true if the channel is active
	 */
	public boolean isConnected() {
		return this.connection != null && this.session != null;
	}

	/**
	 * Disconnects the connection and the session if either of them are active
	 */
	public void disconnect() {
		if (this.session == null) {
			this.session.close();
			this.session = null;
		}
		
		if (this.connection != null) {
			this.connection.close();
			this.connection = null;
		}
	} 
	

	/**
	 * Sends the message over the connections. If the connection is yet not initialized, it is started then.
	 * 
	 * @param command - the command to execute
	 * @throws CommunicationException
	 */
	public void send(String command) throws CommunicationException {
		try {
			this.connect();
			this.sender.write(command.getBytes());
		} catch (IOException e) {
			String msg = "Error writing on the ssh channel";
			if (logger.isDebugEnabled()) {
				logger.error(msg, e);
				throw new RuntimeException(msg, e);
			}
		}
	}
	
	public void callback(String payload) {
		this.details.getSshMultiplexedConnector().doCallback(payload, this.details.getUsername());
	}

	public Session getSession() {
		return session;
	}

	public OutputStream getSender() {
		return sender;
	}

	public InputStream getReceiver() {
		return receiver;
	}

	public BlockingQueue<String> getReceiverBuffer() {
		return receiverBuffer;
	}

	public int getReceiverBufferSize() {
		return receiverBufferSize;
	}
	
}