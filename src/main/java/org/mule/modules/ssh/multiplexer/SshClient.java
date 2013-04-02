package org.mule.modules.ssh.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
	
	/** Thread processing stdout and stderr. It contains blocking calls, and has thus been put in a separate Thread */
	private SshThread responseConsumerThread;
	
	private int receiverBufferSize;
	
	/** Ssh stdin */
	private OutputStream sender = null;
	/** Ssh stdout */
	private InputStream receiver = null;
	/** Ssh stderr */
	private InputStream error = null;

	public SshClient(SshConnectionDetails details) {
		this.details = details;
		this.receiverBufferSize = details.getReceiverBufferSize();
	}
	
	public void connect() throws IOException {
		if (this.connection == null) {

			Connection connection = new Connection(this.details.getHost(),this.details.getPort());
			connection.connect();
			boolean isAuthenticated = connection.authenticateWithPassword(this.details.getUsername(), this.details.getPassword());

			if (isAuthenticated) {
				this.connection = connection;
			} else {
				throw new IOException("Authentication failed.");
			}

			Session session = this.connection.openSession();
			
			if (this.details.isShellMode()) {
				session.requestPTY("mulessh", 500, 500, 0, 0, null);
				session.startShell();
			}
			
			this.session = session;
			
			this.sender = session.getStdin();
			this.receiver = session.getStdout();
			this.error = session.getStderr();

			this.responseConsumerThread = new ResponseConsumerThread(this);
			this.responseConsumerThread.start();
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
			String msg = "Error writing on the ssh channel for '" + details.logString() + "'";
			if (logger.isDebugEnabled()) {
				logger.debug(msg, e);
			}
			logger.error(msg);
			throw new RuntimeException(msg, e);
		}
	}
	
	public void callback(String payload) {
		this.details.getSshMultiplexedConnector().doCallback(payload, this.details.getUsername());
	}

	public Session getSession() {
		return session;
	}

	/**
	 * ssh stdin InputStream
	 */
	public OutputStream getSender() {
		return sender;
	}

	/**
	 * ssh stdout InputStream
	 */
	public InputStream getReceiver() {
		return receiver;
	}

	/**
	 * ssh stderr InputStream
	 */
	public InputStream getError() {
		return error;
	}

	/**
	 * Connection details.
	 */
	public SshConnectionDetails getDetails() {
		return details;
	}

	public int getReceiverBufferSize() {
		return receiverBufferSize;
	}

}
