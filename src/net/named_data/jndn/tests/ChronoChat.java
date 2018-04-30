package net.named_data.jndn.tests;

import com.google.protobuf.InvalidProtocolBufferException;
import net.named_data.jndn.Data;
import net.named_data.jndn.Face;
import net.named_data.jndn.Interest;
import net.named_data.jndn.InterestFilter;
import net.named_data.jndn.Name;
import net.named_data.jndn.OnData;
import net.named_data.jndn.OnInterestCallback;
import net.named_data.jndn.OnRegisterFailed;
import net.named_data.jndn.OnTimeout;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.sync.ChronoSync2013;
import net.named_data.jndn.tests.ChatbufProto.ChatMessage;
import net.named_data.jndn.util.Blob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class ChronoChat implements ChronoSync2013.OnInitialized,
		ChronoSync2013.OnReceivedSyncState, OnData, OnInterestCallback, Chat {

	public ChronoChat
		(String screenName, String chatRoom, Name hubPrefix, Face face,
		 KeyChain keyChain, Name certificateName)
	{
		screenName_ = screenName;
		chatRoom_ = chatRoom;
		face_ = face;
		keyChain_ = keyChain;
		certificateName_ = certificateName;
		heartbeat_ = this.new Heartbeat();

		// This should only be called once, so get the random string here.
		chatPrefix_ = new Name(hubPrefix).append(chatRoom_).append(getRandomString());
		int session = (int)Math.round(getNowMilliseconds() / 1000.0);
		userName_ = screenName_ + session;
		try {
			sync_ = new ChronoSync2013(
				this,
				this,
				chatPrefix_,
				new Name("/ndn/broadcast/ChronoChat-0.3").append(chatRoom_),
				session,
				face,
				keyChain,
				certificateName,
				syncLifetime_,
				RegisterFailed.onRegisterFailed_);
		} catch (Exception e) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, "failed to create ChronoSync2013 class", e);
			System.exit(1);
			return;
		}

		try {
			face.registerPrefix(chatPrefix_, this, RegisterFailed.onRegisterFailed_);
		} catch (IOException | SecurityException ex) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	@Override
	public abstract void recordMessageReceipt(String from, String msg);

	@Override
	public abstract void updateUser(String oldName, String newName);

	@Override
	public abstract void addUser(String name);

	@Override
	public void
	sendMessage(String chatMessage) {
		if (messageCache_.size() == 0) {
			messageCacheAppend(ChatMessage.ChatMessageType.JOIN, "xxx");
		}

		// Ignore an empty message.
		// forming Sync Data Packet.
		try {
			if (!chatMessage.equals("")) {
				sync_.publishNextSequenceNo();
				messageCacheAppend(ChatMessage.ChatMessageType.CHAT, chatMessage);
				Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,screenName_ + ": " + chatMessage);
			}
		}
		catch(Exception e) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE,
				null, e);
		}
	}

	@Override
	public void leave() {
		try {
			sync_.publishNextSequenceNo();
			messageCacheAppend(ChatMessage.ChatMessageType.LEAVE, "xxx");
		}
		catch (Exception e) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE,
				null, e);
		}
	}

	/**
	 * Get the current time in milliseconds.
	 * @return  The current time in milliseconds since 1/1/1970, including
	 * fractions of a millisecond.
	 */
	 static double
	getNowMilliseconds() { return (double)System.currentTimeMillis(); }

	// initial: push the JOIN message in to the messageCache_, update roster and
	// start the heartbeat.
	// (Do not call this. It is only public to implement the interface.)
	public final void
	onInitialized()
	{
		Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,"on initialzed for...: " + screenName_);
		// Set the heartbeat timeout using the Interest timeout mechanism. The
		// heartbeat() function will call itself again after a timeout.
		// TODO: Are we sure using a "/local/timeout" interest is the best future call approach?
		Interest timeout = new Interest(new Name("/local/timeout"));
		timeout.setInterestLifetimeMilliseconds(60000);
		try {
			face_.expressInterest(timeout, DummyOnData.onData_, heartbeat_);
		} catch (IOException ex) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
			return;
		}

		if (roster_.indexOf(userName_) < 0) {
			addToRoster(userName_);
			Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,"Member: " + screenName_);
			Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,screenName_ + ": Join");
			messageCacheAppend(ChatMessage.ChatMessageType.JOIN, "xxx");
		}
	}

	// sendInterest: Send a ChronoChat Interest to fetch chat messages after the
	// user gets the Sync data packet back but will not send interest.
	// (Do not call this. It is only public to implement the interface.)
	public final void
	onReceivedSyncState(List syncStates, boolean isRecovery)
	{
		// This is used by onData to decide whether to display the chat messages.
		isRecoverySyncState_ = isRecovery;

		ArrayList sendList = new ArrayList(); // of String
		ArrayList sessionNoList = new ArrayList(); // of long
		ArrayList sequenceNoList = new ArrayList(); // of long
		for (int j = 0; j < syncStates.size(); ++j) {
			ChronoSync2013.SyncState syncState = (ChronoSync2013.SyncState)syncStates.get(j);
			Name nameComponents = new Name(syncState.getDataPrefix());
			String tempName = nameComponents.get(-1).toEscapedString();
			long sessionNo = syncState.getSessionNo();
			if (!tempName.equals(screenName_)) {
				int index = -1;
				for (int k = 0; k < sendList.size(); ++k) {
					if (((String)sendList.get(k)).equals(syncState.getDataPrefix())) {
						index = k;
						break;
					}
				}
				if (index != -1) {
					sessionNoList.set(index, sessionNo);
					sequenceNoList.set(index, syncState.getSequenceNo());
				}
				else {
					sendList.add(syncState.getDataPrefix());
					sessionNoList.add(sessionNo);
					sequenceNoList.add(syncState.getSequenceNo());
				}
			}
		}

		for (int i = 0; i < sendList.size(); ++i) {
			String uri = (String)sendList.get(i) + "/" + (long)sessionNoList.get(i) +
				"/" + (long)sequenceNoList.get(i);
			Interest interest = new Interest(new Name(uri));
			interest.setInterestLifetimeMilliseconds(syncLifetime_);
			try {
				face_.expressInterest(interest, this, ChatTimeout.onTimeout_);
			} catch (IOException ex) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}
		}
	}

	// Send back a ChronoChat Data Packet which contains the user's message.
	// (Do not call this. It is only public to implement the interface.)
	public final void
	onInterest
	(Name prefix, Interest interest, Face face, long interestFilterId,
	 InterestFilter filter)
	{
		ChatMessage.Builder builder = ChatMessage.newBuilder();
		long sequenceNo = Long.parseLong(interest.getName().get(chatPrefix_.size() + 1).toEscapedString());
		boolean gotContent = false;
		for (int i = messageCache_.size() - 1; i >= 0; --i) {
			CachedMessage message = (CachedMessage)messageCache_.get(i);
			if (message.getSequenceNo() == sequenceNo) {
				if (!message.getMessageType().equals(ChatMessage.ChatMessageType.CHAT)) {
					builder.setFrom(screenName_);
					builder.setTo(chatRoom_);
					builder.setType(message.getMessageType());
					builder.setTimestamp((int)Math.round(message.getTime() / 1000.0));
				}
				else {
					builder.setFrom(screenName_);
					builder.setTo(chatRoom_);
					builder.setType(message.getMessageType());
					builder.setData(message.getMessage());
					builder.setTimestamp((int)Math.round(message.getTime() / 1000.0));
				}
				gotContent = true;
				break;
			}
		}

		if (gotContent) {
			ChatMessage content = builder.build();
			byte[] array = content.toByteArray();
			Data data = new Data(interest.getName());
			data.setContent(new Blob(array, false));
			try {
				keyChain_.sign(data, certificateName_);
			} catch (SecurityException ex) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}
			try {
				face.putData(data);
			} catch (IOException ex) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}

	// Process the incoming ChronoChat data.
	// (Do not call this. It is only public to implement the interface.)
	public final void
	onData(Interest interest, Data data)
	{
		ChatMessage content;
		try {
			content = ChatMessage.parseFrom(data.getContent().getImmutableArray());
		} catch (InvalidProtocolBufferException ex) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
			return;
		}
		if (getNowMilliseconds() - content.getTimestamp() * 1000.0 < 120000.0) {
			String name = content.getFrom();
			String prefix = data.getName().getPrefix(-2).toUri();
			long sessionNo = Long.parseLong(data.getName().get(-2).toEscapedString());
			long sequenceNo = Long.parseLong(data.getName().get(-1).toEscapedString());
			String nameAndSession = name + sessionNo;

			int l = 0;
			//update roster
			while (l < roster_.size()) {
				String entry = (String)roster_.get(l);
				String tempName = entry.substring(0, entry.length() - 10);
				long tempSessionNo = Long.parseLong(entry.substring(entry.length() - 10));
				if (!name.equals(tempName) && !content.getType().equals(ChatMessage.ChatMessageType.LEAVE))
					++l;
				else {
					if (name.equals(tempName) && sessionNo > tempSessionNo) {
						roster_.set(l, nameAndSession);
						setRoster(l, nameAndSession);
					}
					break;
				}
			}

			if (l == roster_.size()) {
				addToRoster(nameAndSession);
				Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,name + ": Join");
			}

			// Set the alive timeout using the Interest timeout mechanism.
			// TODO: Are we sure using a "/local/timeout" interest is the best future call approach?
			Interest timeout = new Interest(new Name("/local/timeout"));
			timeout.setInterestLifetimeMilliseconds(120000);
			try {
				face_.expressInterest
					(timeout, DummyOnData.onData_,
						this.new Alive(sequenceNo, name, sessionNo, prefix));
			} catch (IOException ex) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}

			// isRecoverySyncState_ was set by sendInterest.
			// TODO: If isRecoverySyncState_ changed, this assumes that we won't get
			//   data from an interest sent before it changed.
			if (content.getType().equals(ChatMessage.ChatMessageType.CHAT) &&
				!isRecoverySyncState_ && !content.getFrom().equals(screenName_)) {
				recordMessageReceipt(content.getFrom(), content.getData());
			}
			else if (content.getType().equals(ChatMessage.ChatMessageType.LEAVE)) {
				// leave message
				int n = roster_.indexOf(nameAndSession);
				if (n >= 0 && !name.equals(screenName_)) {
					roster_.remove(n);
					Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,name + ": Leave");
				}
			}
		}
	}


	public void addToRoster(String name) {
		roster_.add(name);
		addUser(name);
	}

	public void setRoster(int i, String newName){
		try{
			String oldName = (String) roster_.get(i);
			if (oldName != null) {
				roster_.set(i, newName);
				updateUser(oldName, newName);
			}
		}
		catch(Exception e) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, "could not setRoster with newName: " + newName, e);
		}
	}

	private static class ChatTimeout implements OnTimeout {
		public final void
		onTimeout(Interest interest) {
			Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,"Timeout waiting for chat data");
		}

		public final static OnTimeout onTimeout_ = new ChatTimeout();
	}

	/**
	 * This repeatedly calls itself after a timeout to send a heartbeat message
	 * (chat message type HELLO).
	 * This method has an "interest" argument because we use it as the onTimeout
	 * for Face.expressInterest.
	 */
	private class Heartbeat implements OnTimeout {
		public final void
		onTimeout(Interest interest) {
			if (messageCache_.size() == 0)
				messageCacheAppend(ChatMessage.ChatMessageType.JOIN, "xxx");

			try {
				sync_.publishNextSequenceNo();
			} catch (IOException | SecurityException ex) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}
			messageCacheAppend(ChatMessage.ChatMessageType.HELLO, "xxx");

			// Call again.
			// TODO: Are we sure using a "/local/timeout" interest is the best future call approach?
			Interest timeout = new Interest(new Name("/local/timeout"));
			timeout.setInterestLifetimeMilliseconds(60000);
			try {
				face_.expressInterest(timeout, DummyOnData.onData_, heartbeat_);
			} catch (IOException ex) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}

	/**
	 * This is called after a timeout to check if the user with prefix has a newer
	 * sequence number than the given temp_seq. If not, assume the user is idle and
	 * remove from the roster and print a leave message.
	 * This is used as the onTimeout for Face.expressInterest.
	 */
	private class Alive implements OnTimeout {
		public Alive(long tempSequenceNo, String name, long sessionNo, String prefix)
		{
			tempSequenceNo_ = tempSequenceNo;
			name_ = name;
			sessionNo_ = sessionNo;
			prefix_ = prefix;
		}

		public final void
		onTimeout(Interest interest)
		{
			long sequenceNo;
			sequenceNo = sync_.getProducerSequenceNo(prefix_, sessionNo_);
			String nameAndSession = name_ + sessionNo_;
			int n = roster_.indexOf(nameAndSession);
			if (sequenceNo != -1 && n >= 0) {
				if (tempSequenceNo_ == sequenceNo) {
					roster_.remove(n);
					Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,name_ + ": Leave");
				}
			}
		}

		private final long tempSequenceNo_;
		private final String name_;
		private final long sessionNo_;
		private final String prefix_;
	}

	/**
	 * Append a new CachedMessage to messageCache_, using given messageType and message,
	 *
	 * the sequence number from sync_.getSequenceNo() and the current time. Also
	 * remove elements from the front of the cache as needed to keep
	 * the size to maxMessageCacheLength_.
	 */
	private void
	messageCacheAppend(ChatMessage.ChatMessageType messageType, String message)
	{
		long seqNo;
		seqNo = sync_.getSequenceNo();

		messageCache_.add(new CachedMessage
			(seqNo, messageType, message, getNowMilliseconds()));
		while (messageCache_.size() > maxMessageCacheLength_)
			messageCache_.remove(0);
	}

	// Generate a random name for ChronoSync.
	private static String
	getRandomString()
	{
		String seed = "qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM0123456789";
		String result = "";
		Random random = new Random();
		for (int i = 0; i < 10; ++i) {
			// Using % means the distribution isn't uniform, but that's OK.
			int position = random.nextInt(256) % seed.length();
			result += seed.charAt(position);
		}

		return result;
	}

	private static class RegisterFailed implements OnRegisterFailed {
		public final void
		onRegisterFailed(Name prefix)
		{
			Logger.getLogger(ChronoChat.class.getName()).log(Level.INFO,"Register failed for prefix " + prefix.toUri());
			//System.exit(1);
		}

		public final static OnRegisterFailed onRegisterFailed_ = new RegisterFailed();
	}

	// This is a do-nothing onData for using expressInterest for timeouts.
	// This should never be called.
	private static class DummyOnData implements OnData {
		public final void
		onData(Interest interest, Data data) {}

		public final static OnData onData_ = new DummyOnData();
	}

	private static class CachedMessage {
		public CachedMessage
			(long sequenceNo, ChatMessage.ChatMessageType messageType, String message, double time)
		{
			sequenceNo_ = sequenceNo;
			messageType_ = messageType;
			message_ = message;
			time_ = time;
		}

		public final long
		getSequenceNo() { return sequenceNo_; }

		public final ChatMessage.ChatMessageType
		getMessageType() { return messageType_; }

		public final String
		getMessage() { return message_; }

		public final double
		getTime() { return time_; }

		private final long sequenceNo_;
		private final ChatMessage.ChatMessageType messageType_;
		private final String message_;
		private final double time_;
	};

	public void pumpFaceAwhile(long awhile) {
		pumpFaceAwhile(face_, awhile);
	}

	public static void pumpFaceAwhile(Face face, long awhile) {
		long startTime0 = System.currentTimeMillis();
		long timeNow0 = System.currentTimeMillis();
		while((timeNow0 - startTime0) <= awhile) {
			timeNow0 = System.currentTimeMillis();
			try {
				face.processEvents();
				Thread.sleep(10);
			}
			catch (Exception e) {
				Logger.getLogger(ChronoChat.class.getName()).log(Level.SEVERE,
					"failed in pumpFaceAwhile", e);
			}
		}

	}

	// Use a non-template ArrayList so it works with older Java compilers.
	protected final ArrayList messageCache_ = new ArrayList(); // of
	// CachedMessage
	protected final ArrayList roster_ = new ArrayList(); // of String
	protected final int maxMessageCacheLength_ = 100;
	protected boolean isRecoverySyncState_ = true;
	protected final String screenName_;
	protected final String chatRoom_;
	public final String userName_;
	protected final Name chatPrefix_;
	protected final double syncLifetime_ = 5000.0; // milliseconds
	protected ChronoSync2013 sync_;
	protected final Face face_;
	protected final KeyChain keyChain_;
	protected final Name certificateName_;
	protected final OnTimeout heartbeat_;
}