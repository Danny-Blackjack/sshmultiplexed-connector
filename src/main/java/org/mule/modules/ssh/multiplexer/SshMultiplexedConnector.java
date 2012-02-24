/**
 * Mule Development Kit
 * Copyright 2010-2011 (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * This file was automatically generated by the Mule Development Kit
 */
package org.mule.modules.ssh.multiplexer;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.mule.DefaultMuleEvent;
import org.mule.DefaultMuleMessage;
import org.mule.MessageExchangePattern;
import org.mule.api.ConnectionException;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.annotations.Configurable;
import org.mule.api.annotations.Module;
import org.mule.api.annotations.Processor;
import org.mule.api.annotations.param.Default;
import org.mule.api.annotations.param.Optional;
import org.mule.api.context.MuleContextAware;
import org.mule.construct.Flow;
import org.mule.modules.ssh.multiplexer.exception.CommunicationException;
import org.mule.session.DefaultMuleSession;

/**
 * Cloud Connector for ssh that is capable to handle multiple session from
 * different users and reusing the session of any given user if he wants to
 * send multiple messages.
 * 
 * Notice that while this connector can handle N sessions for N users, each user can only have
 * 1 active session at a time
 *
 * @author marianogonzalez
 */
@Module(name="sshmultiplexedconnector", schemaVersion="1.0")
public class SshMultiplexedConnector implements MuleContextAware {
	
	private static final Logger logger = Logger.getLogger(SshMultiplexedConnector.class);
	
	private static final String SSH_CALLBACK_USER = "SSH_CALLBACK_USER";

	private MuleContext muleContext;
	
	/**
	 * IP address for the target host
	 */
	@Configurable
	private String host;
	
	/**
	 * TCP port in which the host is listening
	 */
	@Configurable
	private int port;
	
	/**
	 * message timeout
	 */
	@Configurable
	private int timeout;
	
	/**
	 * If not null, a flow with this name will be
	 * fetched from the registry and invoked everytime
	 * data is received from the other end. Keep in mind that SSH
	 * is a full duplex protocol, meaning that you can receive data at any time,
	 * not just as a reply to a message you have sent.
	 */
	@Configurable
	private String callbackFlowName;
	
	/**
	 * if true, the connection will be opened in shell mode, meaning that
	 * the session context will be maintained from the moment it's stablished
	 * until it's closed. If false, then only single commands will be allowed and no
	 * context will be passed from one invocation to the next.
	 */
	@Configurable
	private boolean shellMode = false;
	
	/**
	 * The flow that will receive callback invocations
	 */
	private Flow callbackFlow = null;

	/**
	 * Instance of {@link org.mule.modules.ssh.multiplexer.SshConnectionManager}
	 * to delegate the connection handling
	 * @see org.mule.modules.ssh.multiplexer.SshConnectionManager
	 */
	private SshConnectionManager connectionManager;
	
	/**
	 * The size of the receiver buffer in bytes. Defaults to 8192
	 * and must be greater or equal to 1
	 */
	@Configurable
	@Optional
	private Integer receiverBufferSize = 8192; 
	
    /**
     * Instanciates the connectionManager.
     * Actual ssh connections are lazily created by 
     * {@link org.mule.modules.ssh.multiplexer.SshMultiplexedConnector.connectionManager}
     *
     * @throws ConnectionException
     */
    @PostConstruct
    public void connect() throws ConnectionException {
        this.connectionManager = new SshConnectionManager();
    }
    
    private Flow callbackFlowLookup() {
    	if (this.callbackFlow == null) {
    		this.callbackFlow = (Flow) this.muleContext.getRegistry().lookupFlowConstruct(this.callbackFlowName);
    		
    		if (this.callbackFlow == null) {
    			throw new IllegalArgumentException("Could not find callback flow with name " + this.callbackFlowName);
    		}
    		
    	}
    	
    	return this.callbackFlow;
    }

    /**
     * Releases all the active ssh connections
     * and deallocates 
     * {@link org.mule.modules.ssh.multiplexer.SshMultiplexedConnector.connectionManager}
     */
    public void disconnect() {
    	this.connectionManager.releaseAll();
    	this.connectionManager = null;
    }

    /**
     * creates/reuses a ssh connection to the host login in as username.
     * Notice that after sending the message the session is kept active. It is up to you to release it.
     * 
     * To release a session, the username can send {@org.mule.modules.ssh.multiplexer.SshMultiplexedConnector.DISCONNECT_STRING}
     * as content. This will cause {@link org.mule.modules.ssh.multiplexer.SshMultiplexedConnector.release(String)}
     * to be invoked
     * 
     * {@sample.xml ../../../doc/SshMultiplexedConnector-connector.xml.sample sshmultiplexedconnector:send}
     * 
     * @see org.mule.modules.ssh.multiplexer.SshMultiplexedConnector.release(String)
     * @param username - the username to use at remote authentication
     * @param password - the password to use at remote authentication
     * @param content - the content to send
     * @param breakLine - if true, then 
     * @return Accumulates text response from the server until the receiving buffer is empty and then
     * returns all of the content at once.
     */
    @Processor
    public void send(String username,
    				 String password,
    				 String content,
    				 @Optional @Default("false")
    				 boolean breakLine) throws Exception {
    	
    	SshConnectionDetails details = this.newConnectionDetails();
    	details.setUsername(username);
    	details.setPassword(password);

    	SshClient client = null;
    	
    	try {
    		client = this.connectionManager.getConnection(details);
    	} catch (CommunicationException e) {
    		this.doCallback("Could not connect", username);
    	}
    	
    	try {
    		client.send(breakLine ? content + "\n" : content);
    	} catch (CommunicationException e) {
    		this.doCallback(ExceptionUtils.getFullStackTrace(e), username);
    	}
    }
    
    /**
     * sends the message to the responseFlow if not null
     * @param message - the message. If null then this method does nothing
     */
    protected void doCallback(String response, String username) {
    	if (!StringUtils.isEmpty(response)) {
    		
    		Map<String, Object> inbound = new HashMap<String, Object>();
    		inbound.put(SSH_CALLBACK_USER, username);
    		
    		MuleMessage message = new DefaultMuleMessage(response, this.muleContext);
    		message.setOutboundProperty(SSH_CALLBACK_USER, username);
    		
    		MuleEvent event = new DefaultMuleEvent(message, MessageExchangePattern.REQUEST_RESPONSE, new DefaultMuleSession(this.callbackFlow, this.muleContext));
    		
    		try {
    			this.callbackFlowLookup().process(event).getMessage();
    		} catch (MuleException e) {
    			if (logger.isDebugEnabled()) {
    				logger.error("Error invoking callback flow", e);
    			}
    			throw new RuntimeException(e);
    		}
    	}
    }
    
    /**
     * Releases the ssh connection associated with the username (if any).
     * It does so by invoking {@link org.mule.modules.ssh.multiplexer.SshConnectionManager.release(String)}
     * {@sample.xml ../../../doc/SshMultiplexedConnector-connector.xml.sample sshmultiplexedconnector:release}
     * 
     * @param username - the username whose connection we want to free
     * @see org.mule.modules.ssh.multiplexer.SshConnectionManager.release(String)
     */
    @Processor
    public void release(String username) {
    	this.connectionManager.release(username);
    }
    
    /**
     * Creates an instance of {@link org.mule.modules.ssh.multiplexer.SshConnectionDetails}
     * with the host and port properties already initialized
     * 
     * @see org.mule.modules.ssh.multiplexer.SshConnectionDetails
     * @return
     */
    private SshConnectionDetails newConnectionDetails() {
    	SshConnectionDetails details = new SshConnectionDetails();
    	details.setHost(this.host);
    	details.setPort(port);
    	details.setTimeout(timeout);
    	details.setShellMode(this.shellMode);
    	details.setSshMultiplexedConnector(this);
    	details.setReceiverBufferSize(this.receiverBufferSize);
    	
    	return details;
    }
    
	public void setHost(String host) {
		this.host = host;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void setTimeout(int timeout) {
		this.timeout = timeout;
	}

	public void setShellMode(boolean shellMode) {
		this.shellMode = shellMode;
	}

	public void setCallbackFlowName(String callbackFlowName) {
		this.callbackFlowName = callbackFlowName;
	}

	public Integer getReceiverBufferSize() {
		return receiverBufferSize;
	}

	public void setReceiverBufferSize(Integer receiverBufferSize) {
		if (receiverBufferSize < 1) {
			throw new IllegalArgumentException("ReceiverBufferSize must be greater or equal than 1");
		}
		this.receiverBufferSize = receiverBufferSize;
	}
	
	@Override
	public void setMuleContext(MuleContext context) {
		this.muleContext = context;
		
	}
	
}
