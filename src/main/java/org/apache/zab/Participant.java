/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zab;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.zab.QuorumZab.StateChangeCallback;
import org.apache.zab.QuorumZab.TestState;
import org.apache.zab.proto.ZabMessage;
import org.apache.zab.proto.ZabMessage.AckEpoch;
import org.apache.zab.proto.ZabMessage.Message;
import org.apache.zab.proto.ZabMessage.NewEpoch;
import org.apache.zab.proto.ZabMessage.ProposedEpoch;
import org.apache.zab.proto.ZabMessage.Message.MessageType;
import org.apache.zab.transport.DummyTransport;
import org.apache.zab.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;

/**
 * Participant.
 */
public class Participant implements Runnable,
                                    Transport.Receiver,
                                    ServerState,
                                    Election.ElectionCallback {
  /**
   * Used for communication between nodes.
   */
  protected final Transport transport;

  /**
   * Callback interface for testing purpose.
   */
  protected StateChangeCallback stateChangeCallback = null;

  /**
   * Message queue. The receiving callback simply parses the message and puts
   * it in queue, it's up to leader/follower/election module to take out
   * the message.
   */
  protected final BlockingQueue<MessageTuple> messageQueue =
    new LinkedBlockingQueue<MessageTuple>();

  /**
   * The transaction log.
   */
  protected final Log log;

  /**
   * Configuration of Zab.
   */
  protected final ZabConfig config;

  /**
   * The file to store the last acknowledged epoch.
   */
  private final File fAckEpoch;

  /**
   * The file to store the last proposed epoch.
   */
  private final File fProposedEpoch;

  /**
   * State Machine callbacks.
   */
  protected StateMachine stateMachine = null;

  /**
   * Last committed zxid.
   */
  private Zxid lastCommittedZxid = null;

  /**
   * Elected leader.
   */
  String electedLeader = null;

  /**
   * Used for leader election.
   */
  CountDownLatch leaderCondition;

  private static final Logger LOG = LoggerFactory.getLogger(Participant.class);

  public Participant(ZabConfig config,
                     StateMachine stateMachine,
                     Zxid lastCommittedZxid)
      throws IOException {
    this(stateMachine, null, new TestState(config), lastCommittedZxid);
  }

  Participant(StateMachine stateMachine,
              StateChangeCallback cb,
              TestState initialState,
              Zxid lastCommittedZxid) throws IOException {

    this.config = new ZabConfig(initialState.prop);
    this.stateMachine = stateMachine;
    this.fAckEpoch = new File(config.getLogDir(), "AckEpoch");
    this.fProposedEpoch = new File(config.getLogDir(), "ProposedEpoch");
    this.lastCommittedZxid = lastCommittedZxid;

    if (lastCommittedZxid == null) {
      this.lastCommittedZxid = Zxid.ZXID_NOT_EXIST;
    }

    if (initialState.getLog() == null) {
      // Transaction log file.
      File logFile = new File(this.config.getLogDir(), "transaction.log");
      this.log = new SimpleLog(logFile);
    } else {
      this.log = initialState.getLog();
    }

    LOG.debug("Txn log file for {} is {} ", this.config.getServerId(),
                                            this.config.getLogDir());

    this.stateChangeCallback = cb;
    this.transport = new DummyTransport(this.config.getServerId(), this);
  }


  /**
   * A tuple holds both message and its source.
   */
  static class MessageTuple {
    private final String source;
    private final Message message;

    public MessageTuple(String source, Message message) {
      this.source = source;
      this.message = message;
    }

    String getSource() {
      return this.source;
    }

    Message getMessage() {
      return this.message;
    }
  }

  void send(ByteBuffer request) {
    Message msg = MessageBuilder.buildRequest(request);
    MessageTuple tuple = new MessageTuple(this.config.getServerId(), msg);
    this.messageQueue.add(tuple);
  }

  @Override
  public void onReceived(String source, ByteBuffer message) {
    byte[] buffer = null;

    try {
      // Parses it to protocol message.
      buffer = new byte[message.remaining()];
      message.get(buffer);
      Message msg = Message.parseFrom(buffer);

      if (LOG.isDebugEnabled()) {
        LOG.debug("{} received message from {}: {} ",
                  this.config.getServerId(),
                  source,
                  TextFormat.shortDebugString(msg));
      }

      // Puts the message in message queue.
      this.messageQueue.add(new MessageTuple(source, msg));

    } catch (InvalidProtocolBufferException e) {
      LOG.error("Exception when parse protocol buffer.", e);
      // Puts an invalid message to queue, it's up to handler to decide what
      // to do.
      Message msg = MessageBuilder.buildInvalidMessage(buffer);
      this.messageQueue.add(new MessageTuple(source, msg));
    }
  }

  @Override
  public void onDisconnected(String serverId) {
  }


  /**
   * Starts main logic of participant.
   */
  @Override
  public void run() {

    try {
      if (this.stateChangeCallback != null) {
        this.stateChangeCallback.electing();
      }

      Election electionAlg = new RoundRobinElection();
      startLeaderElection(electionAlg);
      waitLeaderElected();

      LOG.debug("{} selected {} as prospective leader.",
                this.config.getServerId(),
                this.electedLeader);

      if (this.electedLeader.equals(this.config.getServerId())) {
        lead();
      } else {
        follow();
      }
    } catch (Exception e) {
      LOG.warn("Caught election ", e);
    }
  }

  /**
   * Gets the last acknowledged epoch.
   *
   * @return the last acknowledged epoch.
   * @throws IOException in case of IO failures.
   */
  int getAckEpochFromFile() throws IOException {
    try {
      int ackEpoch = FileUtils.readIntFromFile(this.fAckEpoch);
      return ackEpoch;
    } catch (FileNotFoundException e) {
      LOG.debug("File not exist, initialize acknowledged epoch to -1");
      return -1;
    } catch (IOException e) {
      LOG.error("IOException encountered when access acknowledged epoch");
      throw e;
    }
  }

  /**
   * Updates the last acknowledged epoch.
   *
   * @param ackEpoch the updated last acknowledged epoch.
   * @throws IOException in case of IO failures.
   */
  void setAckEpoch(int ackEpoch) throws IOException {
    FileUtils.writeIntToFile(ackEpoch, this.fAckEpoch);
  }

  /**
   * Gets the last proposed epoch.
   *
   * @return the last proposed epoch.
   * @throws IOException in case of IO failures.
   */
  int getProposedEpochFromFile() throws IOException {
    try {
      int pEpoch = FileUtils.readIntFromFile(this.fProposedEpoch);
      return pEpoch;
    } catch (FileNotFoundException e) {
      LOG.debug("File not exist, initialize acknowledged epoch to -1");
      return -1;
    } catch (IOException e) {
      LOG.error("IOException encountered when access acknowledged epoch");
      throw e;
    }
  }

  /**
   * Updates the last proposed epoch.
   *
   * @param pEpoch the updated last proposed epoch.
   * @throws IOException in case of IO failure.
   */
  void setProposedEpoch(int pEpoch) throws IOException {
    FileUtils.writeIntToFile(pEpoch, this.fProposedEpoch);
  }

  /**
   * Gets a message from the queue.
   *
   * @return a message tuple contains the message and its source.
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException it's interrupted.
   */
  MessageTuple getMessage() throws TimeoutException, InterruptedException {
    MessageTuple tuple = messageQueue.poll(config.getTimeout(),
                                           TimeUnit.MILLISECONDS);
    // Checks if timeout.
    if (tuple == null) {
      throw new TimeoutException("Timeout while waiting for the message.");
    }
    return tuple;
  }

  /**
   * Gets the expected message. It will discard messages of unexpected type
   * and source and returns only if the expected message is received.
   *
   * @param type the expected message type.
   * @param source the expected source, null if it can from anyone.
   * @return the message tuple contains the message and its source.
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException it's interrupted.
   */
  MessageTuple getExpectedMessage(MessageType type, String source)
      throws TimeoutException, InterruptedException {
    // Waits until the expected message is received.
    while (true) {
      MessageTuple tuple = getMessage();
      String from = tuple.getSource();

      if (tuple.getMessage().getType() == type &&
          (source == null || source.equals(from))) {
        // Return message only if it's expected type and expected source.
        return tuple;

      } else if (LOG.isDebugEnabled()) {
        LOG.debug("Got an unexpected message from {}: {}",
                  tuple.getSource(),
                  TextFormat.shortDebugString(tuple.getMessage()));
      }
    }
  }

  /**
   * Gets last received proposed epoch.
   */
  @Override
  public int getProposedEpoch() {
    try {
      return getProposedEpochFromFile();
    } catch (IOException e) {
      return -1;
    }
  }

  /**
   * Gets the size of the ensemble.
   */
  @Override
  public int getEnsembleSize() {
    return config.getEnsembleSize();
  }

  /**
   * Gets the server list.
   */
  @Override
  public List<String> getServerList() {
    return config.getPeers();
  }

  /**
   * Gets the minimal quorum size.
   *
   * @return the minimal quorum size
   */
  @Override
  public int getQuorumSize() {
    return getEnsembleSize() / 2 + 1;
  }

  /**
   * Broadcasts the message to all the peers.
   *
   * @param peers the destination peers.
   * @param message the message to be broadcasted.
   */
  void broadcast(Iterator<String> peers, Message message) {
    transport.broadcast(peers, ByteBuffer.wrap(message.toByteArray()));
  }

  /**
   * Sends message to the specific destination.
   *
   * @param dest the destination of the message.
   * @param message the message to be sent.
   */
  void sendMessage(String dest, Message message) {
    transport.send(dest, ByteBuffer.wrap(message.toByteArray()));
  }

  /* -----------------   For ELECTING state only -------------------- */


  void startLeaderElection(Election electionAlg) {
    this.leaderCondition = new CountDownLatch(1);
    electionAlg.initialize(this, this);
  }

  @Override
  public void leaderElected(String serverId) {
    this.electedLeader = serverId;
    this.leaderCondition.countDown();
  }

  void waitLeaderElected() throws InterruptedException {
    this.leaderCondition.await();
  }



  /* -----------------   For BOTH LEADING and FOLLOWING -------------------- */

  /**
   * Synchronizes server's history to peer based on the last zxid of peer.
   * This function is called when the follower syncs its history to leader as
   * initial history or the leader syncs its initial history to followers in
   * synchronization phase. Based on the last zxid of peer, the synchronization
   * can be performed by TRUNCATE, DIFF or SNAPSHOT. The assumption is that
   * the server has all the committed transactions in its transaction log.
   *
   *  . If the epoch of the last transaction is different from the epoch of
   *  the last transaction of this server's. The whole log of the peer's will be
   *  truncated by sending SNAPSHOT message and then this server will
   *  synchronize its history to the peer.
   *
   *  . If the epoch of the last transaction of the peer and this server are
   *  the same, then this server will send DIFF or TRUNCATE to only synchronize
   *  or truncate the diff.
   *
   * @param peerId the id of the peer.
   * @param peerLatestZxid the last zxid of the peer.
   */
  void synchronizePeer(String peerId, Zxid peerLatestZxid) throws IOException {

    LOG.debug("Begins sync {} history to {}(last zxid : {})",
              this.config.getServerId(),
              peerId,
              peerLatestZxid);

    Zxid lastZxid = this.log.getLatestZxid();
    Zxid syncPoint = null;

    if (lastZxid.getEpoch() == peerLatestZxid.getEpoch()) {
      // If the peer has same epoch number as the server.

      if (lastZxid.compareTo(peerLatestZxid) >= 0) {
        // Means peer's history is the prefix of the server's.
        LOG.debug("{}'s history is >= {}'s, sending DIFF.",
                  this.config.getServerId(),
                  peerId);

        syncPoint = new Zxid(peerLatestZxid.getEpoch(),
                             peerLatestZxid.getXid() + 1);

        Message diff = MessageBuilder.buildDiff(lastZxid);
        sendMessage(peerId, diff);

      } else {
        // Means peer's history is the superset of the server's.
        LOG.debug("{}'s history is < {}'s, sending TRUNCATE.",
                  this.config.getServerId(),
                  peerId);

        syncPoint = new Zxid(lastZxid.getEpoch(), lastZxid.getXid() + 1);
        Message trunc = MessageBuilder.buildTruncate(lastZxid);
        sendMessage(peerId, trunc);
      }

    } else {
      // They have different epoch numbers. Truncate all.
      LOG.debug("The last epoch of {} and {} are different, sending SNAPSHOT.",
                this.config.getServerId(),
                peerId);

      syncPoint = new Zxid(0, 0);
      Message snapshot = MessageBuilder.buildSnapshot(lastZxid);
      sendMessage(peerId, snapshot);
    }

    try (Log.LogIterator iter = this.log.getIterator(syncPoint)) {
      while (iter.hasNext()) {
        Transaction txn = iter.next();
        Message prop = MessageBuilder.buildProposal(txn);
        sendMessage(peerId, prop);
      }
    }
  }

  /**
   * Waits synchronization message from peer. This method is called on both
   * leader side and follower side.
   *
   * @param peer the id of the expected peer that synchronization message will
   * come from.
   */
  void waitForSync(String peer)
      throws InterruptedException, TimeoutException, IOException {

    LOG.debug("{} is waiting sync from {}.", this.config.getServerId(), peer);

    Zxid lastZxid = this.log.getLatestZxid();
    // The last zxid of peer.
    Zxid lastZxidPeer = null;
    Message msg = null;
    String source = null;

    // Expects getting message of DIFF or TRUNCATE or SNAPSHOT or PULL_TXN_REQ
    // from elected leader.
    while (true) {
      MessageTuple tuple = getMessage();
      source = tuple.getSource();
      msg = tuple.getMessage();

      if ((msg.getType() != MessageType.DIFF &&
           msg.getType() != MessageType.TRUNCATE &&
           msg.getType() != MessageType.SNAPSHOT &&
           msg.getType() != MessageType.PULL_TXN_REQ) ||
          !source.equals(peer)) {

        LOG.debug("Got unexpected message {} from {}.", msg, source);
        continue;

      } else {
        break;
      }
    }

    if (msg.getType() == MessageType.PULL_TXN_REQ) {
      // PULL_TXN_REQ message. This message is only received at FOLLOWER side.
      LOG.debug("{} got pull transaction request from {}",
                this.config.getServerId(),
                source);

      ZabMessage.Zxid z = msg.getPullTxnReq().getLastZxid();
      lastZxidPeer = new Zxid(z.getEpoch(), z.getXid());

      // Synchronize its history to leader.
      synchronizePeer(source, lastZxidPeer);

      // After synchronization, leader should have same history as this
      // server, so next message should be an empty DIFF.
      MessageTuple tuple = getExpectedMessage(MessageType.DIFF, peer);
      msg = tuple.getMessage();

      ZabMessage.Diff diff = msg.getDiff();
      lastZxidPeer = MessageBuilder.fromProtoZxid(diff.getLastZxid());

      // Check if they are match.
      if (lastZxidPeer.compareTo(lastZxid) != 0) {
        LOG.error("The history of leader and follower are not same.");
        throw new RuntimeException("Expecting leader and follower have same"
                                    + "history.");
      }

      return;
    }

    if (msg.getType() == MessageType.DIFF) {
      // DIFF message.
      LOG.debug("{} got DIFF : {}", this.config.getServerId(), msg);
      ZabMessage.Diff diff = msg.getDiff();
      // Remember last zxid of the peer.
      lastZxidPeer = MessageBuilder.fromProtoZxid(diff.getLastZxid());

      if(lastZxid.compareTo(lastZxidPeer) == 0) {
        // Means the two nodes have exact same history.
        return;
      }

    } else if (msg.getType() == MessageType.TRUNCATE) {
      // TRUNCATE message.
      LOG.debug("{} got TRUNCATE: {}", this.config.getServerId(), msg);
      ZabMessage.Truncate trunc = msg.getTruncate();
      Zxid lastPrefixZxid = MessageBuilder
                            .fromProtoZxid(trunc.getLastPrefixZxid());
      this.log.truncate(lastPrefixZxid);
      return;

    } else {
      // SNAPSHOT message.
      LOG.debug("{} got SNAPSHOT: {}", this.config.getServerId(), msg);
      ZabMessage.Snapshot snap = msg.getSnapshot();
      lastZxidPeer = MessageBuilder.fromProtoZxid(snap.getLastZxid());
      this.log.truncate(Zxid.ZXID_NOT_EXIST);

      // Check if the history of peer is empty.
      if (lastZxidPeer.compareTo(Zxid.ZXID_NOT_EXIST) == 0) {
        return;
      }
    }

    // Get subsequent proposals.
    while (true) {
      MessageTuple tuple = getExpectedMessage(MessageType.PROPOSAL, peer);
      msg = tuple.getMessage();
      source = tuple.getSource();

      LOG.debug("{} got PROPOSAL from {} : {}",
                this.config.getServerId(),
                source,
                msg);

      ZabMessage.Proposal prop = msg.getProposal();
      Zxid zxid = MessageBuilder.fromProtoZxid(prop.getZxid());
      // Accept the proposal.
      this.log.append(MessageBuilder.fromProposal(prop));

      // Check if this is the last proposal.
      if (zxid.compareTo(lastZxidPeer) == 0) {
        return;
      }
    }
  }


  /**
   * Returns all the transactions appear in log.
   *
   * @return a list of transactions.
   */
  List<Transaction> getAllTxns() throws IOException {
    List<Transaction> txns = new ArrayList<Transaction>();

    try(Log.LogIterator iter = this.log.getIterator(new Zxid(0, 0))) {
      while(iter.hasNext()) {
        txns.add(iter.next());
      }
      return txns;
    }
  }


  /**
   * This will be called once receive the COMMIT messge.
   *
   * @param zxid the zxid of the committed transaction.
   */
  void onCommit(Zxid zxid) throws IOException {

    LOG.debug("{} on commit {} {}", this.config.getServerId(), zxid,
                                    this.lastCommittedZxid);

    Zxid tZxid = this.lastCommittedZxid;
    this.lastCommittedZxid = zxid;

    // It will deliver all the transactions from last committed point.
    try (Log.LogIterator iter = this.log.getIterator(tZxid)) {
      if (iter.hasNext()) {

        if (tZxid != Zxid.ZXID_NOT_EXIST) {
          // Skip last one.
          iter.next();
        }

        while (iter.hasNext()) {
          Transaction txn = iter.next();

          if (txn.getZxid().compareTo(zxid) > 0) {
            break;
          }

          this.stateMachine.deliver(txn.getZxid(), txn.getBody());
        }
      }
    }
  }


  /* -----------------   For LEADING state only -------------------- */

  /**
   * Begins executing leader steps. It returns if any exception is caught,
   * which causes it goes back to election phase.
   */
  void lead() {

    Map<String, PeerHandler> quorumSet =
        new HashMap<String, PeerHandler>();

    try {

      /* -- Discovering phase -- */

      LOG.debug("Now {} is in discovering phase.", this.config.getServerId());

      if (stateChangeCallback != null) {
        stateChangeCallback.leaderDiscovering(config.getServerId());
      }

      // Gets the proposed message from a quorum.
      getPropsedEpochFromQuorum(quorumSet);

      // Proposes new epoch to followers.
      proposeNewEpoch(quorumSet);

      // Waits the new epoch is established.
      waitEpochAckFromQuorum(quorumSet);

      LOG.debug("Leader {} established new epoch {}!",
                config.getServerId(),
                getProposedEpochFromFile());

      // Finds one who has the "best" history.
      String serverId = selectSyncHistoryOwner(quorumSet);

      LOG.debug("Leader {} chooses {} to pull its history.",
                this.config.getServerId(),
                serverId);


      /* -- Synchronizing phase -- */

      LOG.debug("Now {} is in synchronizating phase.",
                this.config.getServerId());

      if (stateChangeCallback != null) {
        stateChangeCallback.leaderSynchronizating(getProposedEpochFromFile());
      }

      if (!serverId.equals(this.config.getServerId())) {
        // Pulls history from the server.
        synchronizeFromFollower(serverId);
      }

      // Updates ACK EPOCH of leader.
      setAckEpoch(getProposedEpochFromFile());

      ExecutorService es = Executors
                           .newCachedThreadPool(DaemonThreadFactory.FACTORY);

      // Synchronization is performed in other threads.
      for (PeerHandler ph : quorumSet.values()) {
        ph.setFuture(es.submit(ph));
      }

      waitNewLeaderAckFromQuorum(quorumSet);


      /* -- Broadcasting phase -- */

      LOG.debug("Now {} is in broadcasting phase.", this.config.getServerId());

      if (stateChangeCallback != null) {

        stateChangeCallback.leaderBroadcasting(getAckEpochFromFile(),
                                               getAllTxns());
      }

      // Adds itself in quorumSet.
      PeerHandler lh = new LeaderHandler();
      lh.setFuture(es.submit(lh));
      quorumSet.put(this.config.getServerId(), lh);

      beginBroadcasting(quorumSet);

    } catch (InterruptedException | TimeoutException | IOException e) {
      LOG.error("Leader caught exception", e);
    }
  }

  /**
   * Waits until receives the CEPOCH message from the quorum.
   *
   * @param quorumSet the quorum set.
   * @throws InterruptedException if anything wrong happens.
   * @throws TimeoutException in case of timeout.
   */
  void getPropsedEpochFromQuorum(Map<String, PeerHandler> quorumSet)
      throws InterruptedException, TimeoutException {
    // Gets last proposed epoch from other servers (not including leader).
    while (quorumSet.size() < getQuorumSize() - 1) {
      MessageTuple tuple = getExpectedMessage(MessageType.PROPOSED_EPOCH, null);
      Message msg = tuple.getMessage();
      String source = tuple.getSource();
      ProposedEpoch epoch = msg.getProposedEpoch();

      if (quorumSet.containsKey(source)) {
        throw new RuntimeException("Quorum set has already contained "
            + source + ", probably a bug?");
      }
      quorumSet.put(source, new PeerHandler(source,
                                                epoch.getProposedEpoch()));
    }
    LOG.debug("{} got proposed epoch from a quorum.", config.getServerId());
  }

  /**
   * Finds an epoch number which is higher than any proposed epoch in quorum
   * set and propose the epoch to them.
   *
   * @param quorumSet the quorum set.
   * @throws IOException in case of IO failure.
   */
  void proposeNewEpoch(Map<String, PeerHandler> quorumSet)
      throws IOException {
    List<Integer> epochs = new ArrayList<Integer>();

    // Puts leader's last received proposed epoch in list.
    epochs.add(getProposedEpochFromFile());

    for (PeerHandler stat : quorumSet.values()) {
      epochs.add(stat.getLastProposedEpoch());
    }

    int newEpoch = Collections.max(epochs) + 1;

    // Updates itself's last proposed epoch.
    setProposedEpoch(newEpoch);

    LOG.debug("{} begins proposing new epoch {}",
              config.getServerId(),
              newEpoch);

    // Sends new epoch message to quorum.
    broadcast(quorumSet.keySet().iterator(),
              MessageBuilder.buildNewEpochMessage(newEpoch));
  }

  /**
   * Waits until the new epoch is established.
   *
   * @param quorumSet the quorum set.
   * @throws InterruptedException if anything wrong happens.
   * @throws TimeoutException in case of timeout.
   */
  void waitEpochAckFromQuorum(Map<String, PeerHandler> quorumSet)
      throws InterruptedException, TimeoutException {

    int ackCount = 0;

    // Waits the Ack from all other peers in the quorum set.
    while (ackCount < quorumSet.size()) {
      MessageTuple tuple = getExpectedMessage(MessageType.ACK_EPOCH, null);
      Message msg = tuple.getMessage();
      String source = tuple.getSource();

      if (!quorumSet.containsKey(source)) {
        LOG.warn("The Epoch ACK comes from {} who is not in quorum set, "
                 + "possibly from previous epoch?",
                 source);
        continue;
      }

      ackCount++;

      AckEpoch ackEpoch = msg.getAckEpoch();
      ZabMessage.Zxid zxid = ackEpoch.getLastZxid();

      // Updates follower's f.a and lastZxid.
      PeerHandler ph = quorumSet.get(source);
      ph.setLastAckedEpoch(ackEpoch.getAcknowledgedEpoch());
      ph.setLastProposedZxid(new Zxid(zxid.getEpoch(), zxid.getXid()));
    }

    LOG.debug("{} received ACKs from the quorum set of size {}.",
              config.getServerId(),
              quorumSet.size() + 1);
  }

  /**
   * Finds a server who has the largest acknowledged epoch and longest
   * history.
   *
   * @param quorumSet the quorum set.
   * @return the id of the server
   * @throws IOException
   */
  String selectSyncHistoryOwner(Map<String, PeerHandler> quorumSet)
      throws IOException {
    // L.1.2 Select the history of a follwer f to be the initial history
    // of the new epoch. Follwer f is such that for every f' in the quorum,
    // f'.a < f.a or (f'.a == f.a && f'.zxid <= f.zxid).

    // Get the acknowledged epoch and the latest zxid of the leader.
    int ackEpoch = getAckEpochFromFile();
    Zxid zxid = log.getLatestZxid();
    String serverId = config.getServerId();

    Iterator<Map.Entry<String, PeerHandler>> iter;
    iter = quorumSet.entrySet().iterator();

    while (iter.hasNext()) {
      Map.Entry<String, PeerHandler> entry = iter.next();

      int fEpoch = entry.getValue().getLastAckedEpoch();
      Zxid fZxid = entry.getValue().getLastProposedZxid();

      if (fEpoch > ackEpoch ||
          (fEpoch == ackEpoch && fZxid.compareTo(zxid) > 0)) {
        ackEpoch = fEpoch;
        zxid = fZxid;
        serverId = entry.getKey();
      }
    }

    LOG.debug("{} has largest acknowledged epoch {} and longest history {}",
              serverId, ackEpoch, zxid);

    if (this.stateChangeCallback != null) {
      this.stateChangeCallback.initialHistoryOwner(serverId,
                                                   ackEpoch,
                                                   zxid);
    }

    return serverId;
  }

  /**
   * Pulls the history from the server who has the "best" history.
   *
   * @param serverId the id of the server whose history is selected.
   */
  void synchronizeFromFollower(String serverId)
      throws IOException, TimeoutException, InterruptedException {

    LOG.debug("Begins sync from follower {}.", serverId);

    Zxid lastZxid = log.getLatestZxid();
    Message pullTxn = MessageBuilder.buildPullTxnReq(lastZxid);
    LOG.debug("Last zxid of {} is {}", this.config.getServerId(),
                                       lastZxid);

    sendMessage(serverId, pullTxn);

    waitForSync(serverId);
  }

  /**
   * Waits for synchronization to followers complete.
   *
   * @param quorumSet the followers need to be wait.
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException in case of interrupt.
   */
  void waitNewLeaderAckFromQuorum(Map<String, PeerHandler> quorumSet)
      throws TimeoutException, InterruptedException, IOException {

    LOG.debug("{} is waiting for synchronization to followers complete.",
              this.config.getServerId());

    int completeCount = 0;

    while (completeCount < quorumSet.size()) {
      MessageTuple tuple = getExpectedMessage(MessageType.ACK, null);
      ZabMessage.Ack ack = tuple.getMessage().getAck();
      String source =tuple.getSource();

      Zxid zxid = MessageBuilder.fromProtoZxid(ack.getZxid());

      if (zxid.compareTo(this.log.getLatestZxid()) != 0) {
        LOG.error("The follower {} is not correctly synchronized.", source);

        throw new RuntimeException("The synchronized follower's last zxid"
            + "doesn't match last zxid of current leader.");
      }

      if (!quorumSet.containsKey(source)) {
        LOG.warn("Quorum set doesn't contain {}, a bug?", source);
        continue;
      }
      completeCount++;
    }
  }

  /**
   * Entering broadcasting phase, leader broadcasts proposal to
   * followers.
   *
   * @param quorumSet the quorum set. Now it includes leader itself.
   * @throws InterruptedException if it's interrupted.
   * @throws TimeoutException in case of timeout.
   * @throws IOException in case of IO failure.
   */
  void beginBroadcasting(Map<String, PeerHandler> quorumSet)
      throws TimeoutException, InterruptedException, IOException {

    PeerHandler lh = quorumSet.get(this.config.getServerId());

    // Send commit message to commit all the transactions synced in
    // synchronization phase.
    Message commit = MessageBuilder.buildCommit(this.log.getLatestZxid());
    queueAllPeers(commit, quorumSet);

    while (true) {
      MessageTuple tuple = getMessage();
      Message msg = tuple.getMessage();
      String source = tuple.getSource();

      if (msg.getType() == MessageType.PROPOSED_EPOCH) {

        LOG.debug("Leader {} got PROPOSED_EPOCH.", this.config.getServerId());

      } else {

        if (!quorumSet.containsKey(source)) {
          // In broadcasting phase, the only expected messages come outside
          // the quorum set is PROPOSED_EPOCH.
          LOG.debug("Leader {} got message {} from {} outside quorum.",
                    this.config.getServerId(),
                    msg,
                    source);
          continue;
        }

        if (msg.getType() == MessageType.PROPOSAL) {
          // Got PROPOSAL, it should be sent from LeaderHandler.
          LOG.debug("Leader {} got PROPOSAL.", this.config.getServerId());

          if (!source.equals(this.config.getServerId())) {
            // The proposal should be enqueued by leader itself.
            LOG.warn("Leader {} got PROPOSAL not come from itself.");
            throw new RuntimeException("Proposal not from leader.");
          }

          // Broadcasts to follower.
          queueAllPeers(msg, quorumSet);

        } else if (msg.getType() == MessageType.ACK) {
          // Got ACK from peers.
          LOG.debug("Leader {} got ACK {} from {}.",
                    this.config.getServerId(),
                    msg,
                    source);

          onAck(msg.getAck(), quorumSet, source);

        } else if (msg.getType() == MessageType.REQUEST) {
          // Got REQUEST from user or other peers.
          LOG.debug("Leader {} got REQUEST.", this.config.getServerId());
          // Let LeaderHandler converts the request to transaction.
          lh.queueMessage(msg);

        } else if (msg.getType() == MessageType.HEARTBEAT) {
          // Got HEARTBEAT message.
          LOG.debug("Leader {} got HEARTBEAAT from {}",
                    this.config.getServerId(),
                    source);

        } else {

          LOG.warn("Unexpected messgae : {} from {}", msg, source);

        }
      }
    }
  }

  /**
   * Handles the proposal acknowledgement from followers.
   *
   * @param ack the acknowledgement message.
   * @param quorumSet the quorum set.
   * @param source the source of the mssage.
   */
  void onAck(ZabMessage.Ack ack,
             Map<String, PeerHandler> quorumSet,
             String source) {

    LOG.debug("ON ACK {}", ack);
    Zxid zxid = MessageBuilder.fromProtoZxid(ack.getZxid());
    quorumSet.get(source).setLastAckedZxid(zxid);

    ArrayList<Zxid> zxids = new ArrayList<Zxid>();

    for (PeerHandler ph : quorumSet.values()) {
      zxids.add(ph.getLastAckedZxid());
    }

    // Sorts the last ack zxid of each peer to find one transaction which can
    // be committed safely.
    Collections.sort(zxids);
    Zxid zxidCanCommit = zxids.get(zxids.size() - getQuorumSize());
    LOG.debug("CAN COMMIT : {}", zxidCanCommit);

    // Checks whether we've sent this commit message or not.
    if (!this.lastCommittedZxid.equals(zxidCanCommit)) {
      LOG.debug("Will send commit {} to quorum set.", zxidCanCommit);
      Message commit = MessageBuilder.buildCommit(zxidCanCommit);
      queueAllPeers(commit, quorumSet);
    }
  }

  void queueAllPeers(Message commit, Map<String, PeerHandler> quorumSet) {
    for (PeerHandler ph : quorumSet.values()) {
      ph.queueMessage(commit);
    }
  }


  /* -----------------   For FOLLOWING state only -------------------- */

  /**
   * Begins executing follower steps. It returns if any exception is caught,
   * which causes it goes back to election phase.
   */
  void follow() {

    try {

      /* -- Discovering phase -- */

      LOG.debug("Now {} is in discovering phase.", this.config.getServerId());

      if (stateChangeCallback != null) {
        stateChangeCallback.followerDiscovering(this.electedLeader);
      }

      sendProposedEpoch();

      receiveNewEpoch();


      /* -- Synchronizing phase -- */

      LOG.debug("Now {} is in synchronizating phase.",
                this.config.getServerId());

      if (stateChangeCallback != null) {
        stateChangeCallback.followerSynchronizating(getProposedEpochFromFile());
      }

      waitForSync(this.electedLeader);

      waitNewLeaderMesage();


      /* -- Broadcasting phase -- */

      LOG.debug("Now {} is in broadcasting phase.", this.config.getServerId());

      if (stateChangeCallback != null) {

        stateChangeCallback.followerBroadcasting(getAckEpochFromFile(),
                                                 getAllTxns());

      }

      beginAccepting();

    } catch (InterruptedException | TimeoutException | IOException e) {
      LOG.error("Follower caught exception.", e);
    }
  }

  /**
   * Sends CEPOCH message to its prospective leader.
   * @throws IOException in case of IO failure.
   */
  void sendProposedEpoch() throws IOException {
    Message message = MessageBuilder
                      .buildProposedEpoch(getProposedEpochFromFile());
    sendMessage(this.electedLeader, message);
  }

  /**
   * Waits until receives the NEWEPOCH message from leader.
   *
   * @throws InterruptedException if anything wrong happens.
   * @throws TimeoutException in case of timeout.
   * @throws IOException in case of IO failure.
   */
  void receiveNewEpoch()
      throws InterruptedException, TimeoutException, IOException {

    MessageTuple tuple = getExpectedMessage(MessageType.NEW_EPOCH,
                                            this.electedLeader);

    Message msg = tuple.getMessage();
    String source = tuple.getSource();

    NewEpoch epoch = msg.getNewEpoch();

    if (epoch.getNewEpoch() < getProposedEpochFromFile()) {
      LOG.error("New epoch {} from {} is smaller than last received "
                + "proposed epoch {}",
                epoch.getNewEpoch(),
                source,
                getProposedEpochFromFile());

      throw new RuntimeException("New epoch is smaller than current one.");
    }

    // Updates follower's last proposed epoch.
    setProposedEpoch(epoch.getNewEpoch());

    LOG.debug("{} receives the new epoch proposal {} from {}.",
              config.getServerId(),
              epoch.getNewEpoch(),
              source);

    Zxid zxid = log.getLatestZxid();
    LOG.debug("Get last zxid : {}", zxid);

    // Sends ACK to leader.
    sendMessage(this.electedLeader,
                MessageBuilder.buildAckEpoch(getAckEpochFromFile(),
                                             zxid));
  }

  /**
   * Waits for NEW_LEADER message and sends back ACK and update ACK epoch.
   *
   * @throws TimeoutException in case of timeout.
   * @throws InterruptedException in case of interrupt.
   * @throws IOException in case of IO failure.
   */
  void waitNewLeaderMesage()
      throws TimeoutException, InterruptedException, IOException {

    LOG.debug("{} is waiting for New Leader message from {}.",
              this.config.getServerId(),
              this.electedLeader);

    MessageTuple tuple = getExpectedMessage(MessageType.NEW_LEADER,
                                            this.electedLeader);
    Message msg = tuple.getMessage();
    String source = tuple.getSource();

    LOG.debug("{} got NEW_LEADER message from {} : {}.",
              this.config.getServerId(),
              source,
              msg);

    // NEW_LEADER message.
    ZabMessage.NewLeader nl = msg.getNewLeader();
    int epoch = nl.getEpoch();
    // Sync Ack epoch to disk.
    setAckEpoch(epoch);
    this.log.sync();

    Message ack = MessageBuilder.buildAck(this.log.getLatestZxid());
    sendMessage(source, ack);
  }

  /**
   * Entering broadcasting phase.
   *
   * @throws InterruptedException if it's interrupted.
   * @throws TimeoutException  in case of timeout.
   * @throws IOException in case of IOException.
   */
  void beginAccepting()
      throws TimeoutException, InterruptedException, IOException {

    AckProcessor ackProcessor = new AckProcessor(this.transport);

    SyncProposalProcessor syncProcessor =
        new SyncProposalProcessor(this.log, ackProcessor);

    while (true) {

      MessageTuple tuple = getMessage();
      Message msg = tuple.getMessage();
      String source = tuple.getSource();

      if (msg.getType() == MessageType.PROPOSAL) {

        LOG.debug("Follower {} got PROPOSAL {}.",
                  this.config.getServerId(),
                  msg.getProposal());
        onProposal(msg, syncProcessor);

      } else if (msg.getType() == MessageType.COMMIT) {

        LOG.debug("Follower {} got COMMIT.", this.config.getServerId());
        ZabMessage.Commit commit = msg.getCommit();
        Zxid zxid = MessageBuilder.fromProtoZxid(commit.getZxid());
        onCommit(zxid);

      } else if (msg.getType() == MessageType.REQUEST) {

        LOG.debug("Follower {} got REQUEST.", this.config.getServerId());

      } else if (msg.getType() == MessageType.HEARTBEAT) {

        LOG.debug("Follower {} got HEARTBEAT.", this.config.getServerId());

      } else {

        LOG.warn("Unexpected messgae : {} from {}", msg, source);

      }
    }

  }

  /**
   * It's called when the follower receives a proposal.
   *
   * @param prop the proposal the follower received.
   * @param syncProcessor used to synchronize the proposal to disk
   * and send back acknowledgment.
   */
  void onProposal(Message prop,
                  SyncProposalProcessor syncProcessor) throws IOException {

    Transaction txn = MessageBuilder.fromProposal(prop.getProposal());
    Zxid zxid = txn.getZxid();

    if (zxid.getEpoch() == getAckEpochFromFile()) {
      // Dispatch to SyncProposalProcessor.
      syncProcessor.processRequest(new Request(this.electedLeader, prop));
    } else {
      LOG.error("The proposal has the wrong epoch number {}.", zxid.getEpoch());
      throw new RuntimeException("The proposal has wrong epoch number.");
    }
  }

  /**
   * Handles follower connection.
   */
  class PeerHandler implements Callable<Integer> {
    protected int lastProposedEpoch = -1;
    protected int lastAckedEpoch = -1;
    protected long lastHeartbeatTime;
    protected Zxid lastProposedZxid = null;
    protected Zxid lastAckedZxid = Zxid.ZXID_NOT_EXIST;
    protected final String serverId;
    protected Future<Integer> future = null;
    protected final BlockingQueue<Message> broadcastingQueue =
        new LinkedBlockingQueue<Message>();

    public PeerHandler(String serverId, int epoch) {
      this.serverId = serverId;
      setLastProposedEpoch(epoch);
      updateHeartbeatTime();
    }

    void setFuture(Future<Integer> ft) {
      this.future = ft;
    }

    Future<Integer> getFuture() {
      return this.future;
    }

    long getLastHeartbeatTime() {
      return this.lastHeartbeatTime;
    }

    void updateHeartbeatTime() {
      this.lastHeartbeatTime = System.nanoTime();
    }

    int getLastProposedEpoch() {
      return this.lastProposedEpoch;
    }

    void setLastProposedEpoch(int epoch) {
      this.lastProposedEpoch = epoch;
    }

    int getLastAckedEpoch() {
      return this.lastAckedEpoch;
    }

    void setLastAckedEpoch(int epoch) {
      this.lastAckedEpoch = epoch;
    }

    Zxid getLastProposedZxid() {
      return this.lastProposedZxid;
    }

    void setLastProposedZxid(Zxid zxid) {
      this.lastProposedZxid = zxid;
    }

    void setLastAckedZxid(Zxid zxid) {
      this.lastAckedZxid = zxid;
    }

    Zxid getLastAckedZxid() {
      return this.lastAckedZxid;
    }

    /**
     * Puts message in queue.
     *
     * @param msg the message which will be sent.
     */
    void queueMessage(Message msg) {
      this.broadcastingQueue.add(msg);
    }

    @Override
    public Integer call() throws IOException, InterruptedException {

      LOG.debug("Leader {} is synchronizing to {} (last zxid : {}).",
                config.getServerId(),
                serverId,
                this.lastProposedZxid);

      // First sync to follower.
      synchronizePeer(this.serverId, this.lastProposedZxid);

      // Send NEW_LEADER message to peer.
      Message nl = MessageBuilder.buildNewLeader(getAckEpochFromFile());
      sendMessage(serverId, nl);

      while (true) {
        Message msg = this.broadcastingQueue.take();

        if (msg.getType() == MessageType.PROPOSAL) {
          // Got PROPOSAL message, send it to follower.
          LOG.debug("PeerHandler got PROPOSAL");
          // Sends this proposal to follower.
          sendMessage(this.serverId, msg);

        } else if (msg.getType() == MessageType.COMMIT) {
          // Got COMMIT message, send it to follower.
          LOG.debug("FollowerHandler got COMMIT {}", msg.getCommit());
          // Sends commit to follower.
          sendMessage(this.serverId, msg);
        }
      }
    }
  }

  /**
   * Handles leader itself.
   */
  class LeaderHandler extends PeerHandler {

    // The zxid of the next proposed transaction.
    Zxid nextZxid = null;

    public LeaderHandler() throws IOException {
      super(config.getServerId(), -1);
      this.nextZxid = new Zxid(getAckEpochFromFile(), 0);
    }

    /**
     * Sends message to leader itself by short circuiting.
     *
     * @param msg the message to itself.
     */
    void sendMessage(Message msg) {
      messageQueue.add(new MessageTuple(config.getServerId(), msg));
    }

    @Override
    public Integer call() throws IOException, InterruptedException {

      LOG.debug("LeaderHandler starts.");
      AckProcessor ackProcessor = new AckProcessor(transport);
      SyncProposalProcessor syncProcessor =
          new SyncProposalProcessor(log, ackProcessor);

      while (true) {

        Message msg = broadcastingQueue.take();

        if (msg.getType() == MessageType.PROPOSAL) {
          // Got PROPOSAL message from itself, accept it locally.

          LOG.debug("LeaderHandler got PROPOSAL");
          syncProcessor.processRequest(new Request(this.serverId, msg));

        } else if (msg.getType() == MessageType.COMMIT) {
          // Got COMMIT message from itself, deliver it locally.

          LOG.debug("LeaderHandler got COMMIT {}", msg.getCommit());
          Zxid zxid = MessageBuilder.fromProtoZxid(msg.getCommit().getZxid());
          onCommit(zxid);

        } else if (msg.getType() == MessageType.REQUEST) {
          // Got REQUEST message, convert the REQUEST to PROPOSAL, and
          // short circuit it to leader's message queue..

          LOG.debug("LeaderHandler got REQUEST {}", msg.getRequest());
          ZabMessage.Request req = msg.getRequest();
          ByteBuffer buf = req.getRequest().asReadOnlyByteBuffer();

          // Let users to convert the request to transaction.
          buf = stateMachine.preprocess(this.nextZxid, buf);
          Transaction txn = new Transaction(this.nextZxid, buf);
          ZabMessage.Message prop = MessageBuilder.buildProposal(txn);
          sendMessage(prop);

          // Bump next proposed zxid.
          this.nextZxid = new Zxid(this.nextZxid.getEpoch(),
                                   this.nextZxid.getXid() + 1);
        }
      }
    }
  }

}
