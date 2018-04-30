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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Chat implements ChronoSync2013.OnInitialized,
		ChronoSync2013.OnReceivedSyncState, OnData, OnInterestCallback {

	Map<String, Map<String, Integer>> userByMessageByMessageCount = new HashMap<>();
	public Chat
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
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, "failed to create ChronoSync2013 class", e);
			System.exit(1);
			return;
		}

		try {
			face.registerPrefix(chatPrefix_, this, RegisterFailed.onRegisterFailed_);
		} catch (IOException | SecurityException ex) {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
		}
	}

	// Send a chat message.
	public final void
	sendMessage(String chatMessage) throws IOException, SecurityException
	{
		if (messageCache_.size() == 0) {
			messageCacheAppend(ChatMessage.ChatMessageType.JOIN, "xxx");
		}

		// Ignore an empty message.
		// forming Sync Data Packet.
		if (!chatMessage.equals("")) {
			sync_.publishNextSequenceNo();
			messageCacheAppend(ChatMessage.ChatMessageType.CHAT, chatMessage);
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,screenName_ + ": " + chatMessage);
		}
	}

	// Send leave message and leave.
	public final void
	leave() throws IOException, SecurityException
	{
		sync_.publishNextSequenceNo();
		messageCacheAppend(ChatMessage.ChatMessageType.LEAVE, "xxx");
	}

	/**
	 * Get the current time in milliseconds.
	 * @return  The current time in milliseconds since 1/1/1970, including
	 * fractions of a millisecond.
	 */
	public static double
	getNowMilliseconds() { return (double)System.currentTimeMillis(); }

	// initial: push the JOIN message in to the messageCache_, update roster and
	// start the heartbeat.
	// (Do not call this. It is only public to implement the interface.)
	public final void
	onInitialized()
	{
		Logger.getLogger(Chat.class.getName()).log(Level.FINE,"on initialzed for...: " + screenName_);
		// Set the heartbeat timeout using the Interest timeout mechanism. The
		// heartbeat() function will call itself again after a timeout.
		// TODO: Are we sure using a "/local/timeout" interest is the best future call approach?
		Interest timeout = new Interest(new Name("/local/timeout"));
		timeout.setInterestLifetimeMilliseconds(60000);
		try {
			face_.expressInterest(timeout, DummyOnData.onData_, heartbeat_);
		} catch (IOException ex) {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
			return;
		}

		if (roster_.indexOf(userName_) < 0) {
			addToRoster(userName_);
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"Member: " + screenName_);
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,screenName_ + ": Join");
			messageCacheAppend(ChatMessage.ChatMessageType.JOIN, "xxx");
		}
	}

	// sendInterest: Send a Chat Interest to fetch chat messages after the
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
				Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}
		}
	}

	// Send back a Chat Data Packet which contains the user's message.
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
				Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}
			try {
				face.putData(data);
			} catch (IOException ex) {
				Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}

	// Process the incoming Chat data.
	// (Do not call this. It is only public to implement the interface.)
	public final void
	onData(Interest interest, Data data)
	{
		ChatMessage content;
		try {
			content = ChatMessage.parseFrom(data.getContent().getImmutableArray());
		} catch (InvalidProtocolBufferException ex) {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
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
				Logger.getLogger(Chat.class.getName()).log(Level.FINE,name + ": Join");
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
				Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
				return;
			}

			// isRecoverySyncState_ was set by sendInterest.
			// TODO: If isRecoverySyncState_ changed, this assumes that we won't get
			//   data from an interest sent before it changed.
			if (content.getType().equals(ChatMessage.ChatMessageType.CHAT) &&
				!isRecoverySyncState_ && !content.getFrom().equals(screenName_)) {
				sendMessage(content.getFrom(), content.getData());
			}
			else if (content.getType().equals(ChatMessage.ChatMessageType.LEAVE)) {
				// leave message
				int n = roster_.indexOf(nameAndSession);
				if (n >= 0 && !name.equals(screenName_)) {
					roster_.remove(n);
					Logger.getLogger(Chat.class.getName()).log(Level.FINE,name + ": Leave");
				}
			}
		}
	}

	public void sendMessage(String from, String msg) {
		incMessage(from, msg);
	}

	Map<String, Integer> testCounts = new HashMap<>();
	int particpantNo;
	int participants;
	public void setTestContext(ChronoChatUser cu, int numMessages, int
		participantNo, int participants) {
		ArrayList<String> messages = cu.getMessages(numMessages);
		this.particpantNo = participantNo;
		this.participants = participants;
		for (String m : messages) {
			testCounts.put(m, 0);
		}
		addUser(userName_);
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
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, "could not setRoster with newName: " + newName, e);
		}
	}

	public void updateUser(String oldName, String newName) {
		Logger.getLogger(Chat.class.getName()).log(Level.INFO, "update user. " +
			"oldName" + oldName + ", new name: " + newName);
		Map<String, Integer> testCounts = userByMessageByMessageCount.get(oldName);
		if (testCounts == null) {
			Logger.getLogger(Chat.class.getName()).log(Level.INFO, "need to " +
				"figure out what to do with updateUser, there was no userByMessageByMessageCount " +
				"for them");
			System.exit(1);
		}
		userByMessageByMessageCount.put(newName, copyTestCounts(testCounts));
		userByMessageByMessageCount.remove(oldName);

	}

	public void addUser(String name) {
		if (name.length() > screenName_.length()) {
			if (userByMessageByMessageCount.get(name) == null) {
				Logger.getLogger(Chat.class.getName()).log(Level.INFO, "adding user:" +
					" " + name + "within test context for " + screenName_);

				Map<String, Integer> newTestCounts = copyTestCounts(testCounts);
				userByMessageByMessageCount.put(name, newTestCounts);
				Logger.getLogger(Chat.class.getName()).log(Level.FINE,"participant(s) " + userByMessageByMessageCount.size());
			}
		}
		else {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, "It " +
				"appears a userName without a session number was almost added" +
				" to the userByMessageByMessageCount. That username was: " + name);

		}
	}

	public Map<String, Integer> copyTestCounts(Map<String, Integer> oldTestCounts) {
		Map<String, Integer> newTestCounts = new HashMap<>();
		Iterator it = oldTestCounts.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry pair = (Map.Entry) it.next();
			newTestCounts.put((String)pair.getKey(), (int)pair.getValue());
		}
		return newTestCounts;
	}

	public void incMessage(String name, String message){
		if (name.contains(userName_)) {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, "Not " +
				"incrementing message to myself, it is me. I am: " +
				userName_ + "and I " + "tried to inc message from: " + name);
			return;
		}
		Map<String, Integer> testCounts = getUsersTestCountsFromUserName(name);
		if (testCounts == null) {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, "Asked " +
				"for participant who was not in the map, illegal call: " +
				name);
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, "Valid " +
				"participants");

			System.exit(1);
		}
		testCounts.put(message, testCounts.get(message) + 1);
	}

	public Map<String, Integer> getUsersTestCountsFromUserName(String name) {
		String theKey = getUsersKeyFromUserName(name);
		return userByMessageByMessageCount.get(theKey);
	}

	public String getUsersKeyFromUserName(String name) {
		String theKey = "";
		boolean found = false;
		for ( String key : userByMessageByMessageCount.keySet() ) {
			if (key.contains(name)) {
				theKey = key;
				found = true;
				break;
			}
		}
		if (!found) {
			Logger.getLogger(Chat.class.getName()).log(Level.SEVERE,
				"Couldn't find user with name: " + name);
			System.exit(1);
		}
		return theKey;
	}

	public void submitStats(SyncQueue queue, int numMessages) {
		int messagesSize = numMessages;
		Logger.getLogger(Chat.class.getName()).log(Level.FINE,"Expected " + messagesSize + " messages");

		ArrayList<UserChatSummary> values = new ArrayList<>();

		int duplicates = 0;
		int numLost = 0;

		Iterator it = userByMessageByMessageCount.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry anIndividualsChatData = (Map.Entry) it.next();
			Map<String, Integer> testCounts =
				(Map<String, Integer>) anIndividualsChatData.getValue();

			Iterator ite = testCounts.entrySet().iterator();

			String userName = (String) anIndividualsChatData.getKey();
			if (userName.contains(screenName_))
				continue;

			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"submitStats from within " + screenName_ + " " +
				"for " + userName);
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"reported number of unique messages: " + testCounts
				.size());
			//TODO there is a case where one unique message got recorded 0 times.
			int currDupes = 0;
			int currNumLost = 0;
			int currCount = 0;
			StringBuffer individualResults = new StringBuffer();

			individualResults.append(" [ ");
			while (ite.hasNext()) {
				Map.Entry pair = (Map.Entry) ite.next();
				int count = (int) pair.getValue();
				if (count > 1) {
					int newDupes = count - 1;
					duplicates += newDupes;
					currDupes += newDupes;
					individualResults.append(", +" + Integer.toString(newDupes));
				} else if (count < 1) {
					int newNumLost = 1 - count;
					currNumLost -= newNumLost;
					numLost -= newNumLost;
					individualResults.append(", -" + Integer.toString(newNumLost));
				} else {
					individualResults.append(", 0");
				}
				currCount += count;
			}
			individualResults.append(" ] ");
			Logger.getLogger(Chat.class.getName()).log(Level.FINE, individualResults
				.toString());
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"");
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"count: " + currCount);
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"duplicates: " + currDupes);
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"numLost: " + currNumLost);
			values.add(new UserChatSummary(userName,
				currCount, currDupes, currNumLost));
		}

		queue.enQ(values);
	}

	private static class ChatTimeout implements OnTimeout {
		public final void
		onTimeout(Interest interest) {
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"Timeout waiting for chat data");
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
				Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
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
				Logger.getLogger(Chat.class.getName()).log(Level.SEVERE, null, ex);
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
					Logger.getLogger(Chat.class.getName()).log(Level.FINE,name_ + ": Leave");
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
			Logger.getLogger(Chat.class.getName()).log(Level.FINE,"Register failed for prefix " + prefix.toUri());
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

	// Use a non-template ArrayList so it works with older Java compilers.
	private final ArrayList messageCache_ = new ArrayList(); // of CachedMessage
	private final ArrayList roster_ = new ArrayList(); // of String
	private final int maxMessageCacheLength_ = 100;
	private boolean isRecoverySyncState_ = true;
	private final String screenName_;
	private final String chatRoom_;
	public final String userName_;
	private final Name chatPrefix_;
	private final double syncLifetime_ = 5000.0; // milliseconds
	private ChronoSync2013 sync_;
	private final Face face_;
	private final KeyChain keyChain_;
	private final Name certificateName_;
	private final OnTimeout heartbeat_;
}