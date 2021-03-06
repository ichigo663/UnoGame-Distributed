package unogame.peer;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import sun.rmi.runtime.Log;
import unogame.game.*;
import unogame.gui.GUITable;
import unogame.gui.WinLoseDialog;
import unogame.server.GameRegistration;

import java.util.logging.Logger;

public class GamePeer implements RemotePeer{
    private final static Logger LOGGER = Logger.getLogger(GamePeer.class.getName());
    public HashMap<Integer, RemotePeer> remotePeerHashMap;
    public ReentrantLock ftTokenRecvLock;
    public boolean ftTokenRecv;
    public int[] vectorClock;

    private boolean hasGameToken;
    private boolean hasFTToken;
    private int ID;
    private Timer ftTimer;
    private Timer gameTimer;
    private FTTokenPasserThread ftTokenPasserThread;
    private int tmp_hand_cnt;
    private int turnOfPlayer;
    private UnoPlayer unoPlayer;
    private UnoDeck unoDeck;

    private static final String RMI_OBJ_NAME = "RemotePeer";
    private static final int RMI_PORT = 1099;
    private static final int FT_RING_DIRECTION = 1;

    private int ftTimeout; //in ms
    private int tokenHoldTime = 1000; //in ms
    private int expectedTransmissionTime = 200; //in ms
    private static final int gTimeout = 30000; //in ms

    private GUITable callbackObject; //its for updating the turn Label

    public GamePeer(int id, boolean hasGameToken, boolean hasFTToken, UnoPlayer unoPlayer, UnoDeck unoDeck){
        this.ID = id;
        this.hasGameToken = hasGameToken;
        this.hasFTToken = hasFTToken;
        this.unoPlayer = unoPlayer;
        this.unoDeck = unoDeck;
        remotePeerHashMap = new HashMap<>();
        vectorClock= new int[8];
        turnOfPlayer = 0;
        //LOGGER.info("ID"+this.ID+":"+vectorClock[this.ID-1]);
        initRMIServer();
        initFT();
    }

    public int getTurnOfPlayer() {
        return turnOfPlayer;
    }

    public void setTurnOfPlayer(int turnOfPlayer) {
        this.turnOfPlayer = turnOfPlayer;
        if (callbackObject != null){
            callbackObject.setTurnLabel("Player "+turnOfPlayer);
        }
    }

    public void setCallbackObject(GUITable guiTable){
        callbackObject = guiTable;
    }

    public UnoPlayer getUnoPlayer() {
        return unoPlayer;
    }

    public UnoDeck getUnoDeck() {
        return unoDeck;
    }

    private void initFT(){
        ftTimer = new Timer();
        ftTokenPasserThread = new FTTokenPasserThread();
        ftTokenRecvLock = new ReentrantLock();
        ftTokenRecv = false;
        ftTimeout = tokenHoldTime*expectedTransmissionTime;
    }

    private void updateFTTimeout(){
        ftTimeout = (tokenHoldTime*remotePeerHashMap.size())+(expectedTransmissionTime*remotePeerHashMap.size());
    }

    private void initRMIServer(){
        if (System.getSecurityManager() == null) {
            System.setSecurityManager(new SecurityManager());
        }
        try {
            RemotePeer stub = (RemotePeer) UnicastRemoteObject.exportObject(this, 0);
            Registry registry = LocateRegistry.createRegistry(RMI_PORT);
            registry.rebind(RMI_OBJ_NAME, stub);
            LOGGER.info(RMI_OBJ_NAME+" bound");
        } catch (Exception e) {
            LOGGER.warning(RMI_OBJ_NAME+":");
            e.printStackTrace();
        }
    }

    public void startFTTokenPassing(){
        ftTokenPasserThread.start();
    }

    public void addRemotePeer(String addr) throws NotBoundException, RemoteException{
        Registry registry = LocateRegistry.getRegistry(addr);
        RemotePeer remotePeer = (RemotePeer) registry.lookup(RMI_OBJ_NAME);
        remotePeerHashMap.put(remotePeer.getID(), remotePeer);
        updateFTTimeout();
        LOGGER.info(addr + " added!");
    }

    //RemotePeer Interface implementation
    @Override
    public int getID(){
        return this.ID;
    }

    @Override
    public void getGameToken(int cardsToPick, Color color){
        hasGameToken = true;
        //check if you have received a PLUS card and if you can contest it
        unoPlayer.setCardsToPick(cardsToPick);
        //update GUI
        if (callbackObject != null) {
            callbackObject.allowDrawing();
            callbackObject.allowPlaying();
            callbackObject.setTurnLabel("Your Turn");
            if(color != null)
                UnoRules.setCurrentColor(color);
            callbackObject.setEventLabel(cardsToPick, color);
        }
        if( UnoRules.isSpecialPlayable(unoPlayer.getHand()) ) {
            unoPlayer.setRecvSpecial(true);
        } else {
            for(int i = 0; i < cardsToPick; i++)
                callbackObject.addCard(unoPlayer.getCardfromDeck(unoDeck));
            unoPlayer.setCardsToPick(0);
        }
        //if you think you won but your play has been contested
        if (unoPlayer.getPlayerAboutToWin() == getID() && unoPlayer.getHand().size() > 0){
            unoPlayer.setPlayerAboutToWin(-1);
            try {
                for (int id : remotePeerHashMap.keySet()) {
                    remotePeerHashMap.get(id).announcePlayerAboutToWin(-1);
                }
            } catch (RemoteException e){
                LOGGER.warning("Failed communication with a player");
            }
        }
        LOGGER.info("ID" + this.ID + " : Game token received!");
    }

    @Override
    public void announceSkip() throws RemoteException {
        if(callbackObject != null){
            callbackObject.setEventSkip();
        }
    }

    @Override
    public void announceLost() throws RemoteException {
        killGameTimer();
        ftTokenPasserThread.interrupt();
        callbackObject.disallowDrawing();
        callbackObject.disallowPlaying();
        new WinLoseDialog(callbackObject).showDialog("You Lost!");
    }

    @Override
    public void announcePlayerAboutToWin(int id) throws RemoteException {
        unoPlayer.setPlayerAboutToWin(id);
    }

    @Override
    public void announceWon(int id) throws RemoteException {
        remotePeerHashMap.get(id).playerWon();
    }

    @Override
    public void playerWon() throws RemoteException{
        ftTokenPasserThread.interrupt();
        killGameTimer();
        if( remotePeerHashMap.size() > 0 ) {
            for (int id : remotePeerHashMap.keySet()) {
                remotePeerHashMap.get(id).announceLost();
            }
        }
        callbackObject.disallowDrawing();
        callbackObject.disallowPlaying();
        new WinLoseDialog(callbackObject).showDialog("You Won!");
    }

    public void sendGameToken() throws RemoteException{
        if(hasGameToken){
            killGameTimer();
            //if there is a player whom is about to win
            //and you didn't contest his plus card notify him
            if (unoPlayer.getPlayerAboutToWin() > -1 &&
                    (!unoPlayer.hasPlayedCard() || !unoDeck.getLastDiscardedCard().isPlus() ) ){
                announceWon(unoPlayer.getPlayerAboutToWin());
            }
            //if player has won display a Dialog
            //and tell the others
            if (unoPlayer.getHand().size() == 0 && !unoDeck.getLastDiscardedCard().isPlus()){
                playerWon();
            } else if (unoPlayer.getHand().size() == 0){
                unoPlayer.setPlayerAboutToWin(getID());
                for (int id: remotePeerHashMap.keySet()){
                    remotePeerHashMap.get(id).announcePlayerAboutToWin(getID());
                }
            }
            //this is to propagate a colour change
            if( !unoPlayer.hasPlayedCard() && unoDeck.getLastDiscardedCard().getType() == SpecialType.CHANGECOLOUR
                    || unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS4)
                unoPlayer.setSelectedColor(UnoRules.getCurrentColor());
            //check if you have not contested a PLUS card
            //in that case draw the correct amount from the deck
            if( unoPlayer.hasRecvSpecial() && ( !unoPlayer.hasPlayedCard() || !unoDeck.getLastDiscardedCard().isSamePlus(unoDeck.previousPlayedCard()) ) ){
                for(int i = 0; i < unoPlayer.getCardsToPick(); i++)
                    callbackObject.addCard(unoPlayer.getCardfromDeck(unoDeck));
                unoPlayer.setCardsToPick(0);
            }
            unoPlayer.setRecvSpecial(false);
            int peerID;
            if (unoPlayer.hasPlayedCard() && unoDeck.getLastDiscardedCard().getType() == SpecialType.SKIP) {
                //notify the other player that he's skipping
                int skipID = getNextInRing(UnoRules.getDirection());
                if (skipID != -1){
                    remotePeerHashMap.get(skipID).announceSkip();
                }else{
                    LOGGER.warning("no other peer in game");
                }
                peerID = getNextInRing(UnoRules.getDirection() * 2);
                LOGGER.info("Next peer: "+peerID);
            }
            else
                peerID = getNextInRing(UnoRules.getDirection());
            vectorClock[ this.ID ] = tmp_hand_cnt + 1;
            LOGGER.info("ID" + this.ID + " : Event" + vectorClock[this.ID ]);
            if( remotePeerHashMap.size() == 1 &&
                    ( unoDeck.getLastDiscardedCard().getType() == SpecialType.REVERSE ||
                    unoDeck.getLastDiscardedCard().getType() == SpecialType.SKIP ) ){
                setTurnOfPlayer(getID());
                setGlobalState(unoDeck.getLastDiscardedCard(), UnoRules.getDirection());
                getGameToken(unoPlayer.getCardsToPick(), unoPlayer.getSelectedColor());
            }else if( peerID != -1){
                hasGameToken = false;
                setTurnOfPlayer(peerID);
                setGlobalState(unoDeck.getLastDiscardedCard(), UnoRules.getDirection());
                if( unoPlayer.hasPlayedCard() && unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS2 ) {
                    remotePeerHashMap.get(peerID).getGameToken(unoPlayer.getCardsToPick()+2, unoPlayer.getSelectedColor());
                }
                else if( unoPlayer.hasPlayedCard() && unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS4 ) {
                    remotePeerHashMap.get(peerID).getGameToken(unoPlayer.getCardsToPick()+4, unoPlayer.getSelectedColor());
                }
                else {
                    remotePeerHashMap.get(peerID).getGameToken(0, unoPlayer.getSelectedColor());
                }
            } else {
                LOGGER.info("no other peer in game");
                playerWon();
            }
            unoPlayer.setSelectedColor(null);
            unoPlayer.setCardsToPick(0);
        }
    }

    @Override
    public void getFTToken(){
        ftTokenRecvLock.lock();
        ftTokenRecv = true;
        ftTokenRecvLock.unlock();
        ftTokenPasserThread.lock.lock();
        hasFTToken = true;
        ftTokenPasserThread.recvdFTToken.signal();
        ftTokenPasserThread.lock.unlock();
    }

    @Override
    public void addPlayers(HashMap<Integer, String> peers){
        for (Integer key: peers.keySet()){
            try {
                if (!remotePeerHashMap.containsKey(key))
                    addRemotePeer(peers.get(key));
            }catch(NotBoundException e){
                LOGGER.warning("Player at "+peers.get(key)+" not bound");
            }catch (RemoteException e){
                LOGGER.warning("Failed communication with player at "+peers.get(key));
            }
        }
    }

    @Override
    public void reconfigureRing(ArrayList<Integer> crashedPeers){
        //reconfigure the logical ring
        for(Integer peerID: crashedPeers){
            vectorClock[peerID] = -1; //disable vector clock for this peer
            remotePeerHashMap.remove(peerID);
            LOGGER.info("Removing peer "+peerID);
            if (callbackObject != null){
                callbackObject.disablePlayer(peerID);
            }
        }
        updateFTTimeout();
        LOGGER.info("reconfiguring completed");
    }

    //isAlive procedure has the role
    //of cancelling any pending timer and setting a new one in case
    //the process doing the recovery procedure fails as well
    @Override
    public void isAlive(){
        //cancel scheduled local failure detector
        LOGGER.info("stopping local failure detector, starting recovery timeout");
        killFTTimer();
        scheduleFTTimer(ftTimeout+(ftTimeout*(ID+1)));
    }

    @Override
    public int getClock(){
        int max=0;
        for (int i=0;i<vectorClock.length;i++){
            if( vectorClock[i] != -1 && vectorClock[i]>vectorClock[max] )
                max=i;
        }
        return max;
    }

    @Override
    public boolean hasGToken(){
        return hasGameToken;
    }

    @Override
    public void redoStep() throws RemoteException{
        int peerID;
        if (unoDeck.getLastDiscardedCard().getType() == SpecialType.SKIP) {
            //notify the other player that he's skipping
            int skipID = getNextInRing(UnoRules.getDirection());
            if (skipID != -1){
                remotePeerHashMap.get(skipID).announceSkip();
            }else{
                LOGGER.warning("no other peer in game");
            }
            peerID = getNextInRing(UnoRules.getDirection() * 2);
            LOGGER.info("Next peer -> "+peerID);
        } else {
            peerID = getNextInRing(UnoRules.getDirection());
        }
        if( remotePeerHashMap.size() == 1 &&
                ( unoDeck.getLastDiscardedCard().getType() == SpecialType.REVERSE ||
                        unoDeck.getLastDiscardedCard().getType() == SpecialType.SKIP ) ){
            setTurnOfPlayer(getID());
            setGlobalState(unoDeck.getLastDiscardedCard(), UnoRules.getDirection());
            getGameToken(unoPlayer.getCardsToPick(), UnoRules.getCurrentColor());
        }else if( peerID != -1){
            LOGGER.info("Next peer -> "+peerID);
            setTurnOfPlayer(peerID);
            setGlobalState(unoDeck.getLastDiscardedCard(), UnoRules.getDirection());
            if( unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS2 ) {
                remotePeerHashMap.get(peerID).getGameToken(unoPlayer.getCardsToPick()+2, UnoRules.getCurrentColor());
            }
            else if( unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS4 ) {
                remotePeerHashMap.get(peerID).getGameToken(unoPlayer.getCardsToPick()+4, UnoRules.getCurrentColor());
            }
            else {
                remotePeerHashMap.get(peerID).getGameToken(0, UnoRules.getCurrentColor());
            }
        } else {
            LOGGER.warning("no other peer in game");
        }
        unoPlayer.setSelectedColor(null);
        unoPlayer.setCardsToPick(0);
    }

    private void scheduleFTTimer(int timeout){
        ftTimer = new Timer();
        ftTimer.schedule(new FaultToleranceThread(), timeout);
    }

    //get the next peerID the ring
    //if it return -1 it means there are no neighbours
    private int getNextInRing(int direction){
        int index = getID();
        for( int i = 0; i < GameRegistration.MAX_PLAYERS; i++){
            index += direction;
            if( index >= GameRegistration.MAX_PLAYERS ) {
                index -= GameRegistration.MAX_PLAYERS;
            } else if( index < 0 ){
                index += GameRegistration.MAX_PLAYERS;
            }
            if ( remotePeerHashMap.containsKey(index) ) {
                return index;
            }
        }
        LOGGER.warning("no other peers in the ring");
        return -1;
    }

    //used when either the FTToken is lost
    //or when in passing the token onwards
    //we detect a crashed peer
    private boolean recoveryProcedure(){
        ArrayList<Integer> crashedPeers = new ArrayList<>();
        boolean gameTokenLost = true;
        int maxClockPeer = getID();
        int maxClock = vectorClock[getID()];
        int clock;
        //discover who has crashed
        for(Integer peerID: remotePeerHashMap.keySet()){
            try{
                remotePeerHashMap.get(peerID).isAlive();
            }catch (RemoteException e){
                LOGGER.info("Peer "+peerID+" is down, adding to list of crashed peers");
                crashedPeers.add(peerID);
            }
        }
        //reconfigure the logical ring
        if(crashedPeers.size() > 0) {
            for (Integer peerID : crashedPeers) {
                LOGGER.info("reconfiguring the logical ring");
                remotePeerHashMap.remove(peerID);
                if (callbackObject != null){
                    callbackObject.disablePlayer(peerID);
                }
            }
            updateFTTimeout();
        }
        if(remotePeerHashMap.size() == 0){
            LOGGER.info("The ring is empty");
            try {
                playerWon();
            }catch (RemoteException e){}
            return false;
        }
        //communicate the other peers to reconfigure as well
        if(crashedPeers.size() > 0) {
            for (Integer peerID : remotePeerHashMap.keySet()) {
                try {
                    remotePeerHashMap.get(peerID).reconfigureRing(crashedPeers);
                } catch (RemoteException e) {
                    LOGGER.warning("Peer " + peerID + " is down, the ring will be eventually reconfigured");
                }
            }
        }
        //get the last alive peerID who had the token
        for(Integer peerID: remotePeerHashMap.keySet()){
            try{
                clock = remotePeerHashMap.get(peerID).getClock();
                if( clock != -1 && clock > maxClock ) {
                    maxClockPeer = peerID;
                    maxClock = clock;
//                    LOGGER.info("ID " + maxClockPeer + " reported event "+maxClock);
                }
                //check if the GameToken got lost
                if( remotePeerHashMap.get(peerID).hasGToken() )
                    gameTokenLost = false;
            }catch (RemoteException e){
                LOGGER.warning("RemoteException in getClock() or hasGToken()");
            }
        }
        if ( !hasGToken() && gameTokenLost && maxClockPeer > -1){
            LOGGER.info("Game token lost");
            if (maxClockPeer != getID()) {
                try {
                    LOGGER.info("request ID " + maxClockPeer + " to redoStep()");
                    remotePeerHashMap.get(maxClockPeer).redoStep();
                } catch (RemoteException e) {
                    LOGGER.warning("Peer " + maxClockPeer + " is down, the ring will be eventually reconfigured");
                }
            } else {
                LOGGER.info("executing redoStep() myself");
                try {
                    redoStep();
                }catch (RemoteException e) {
                    LOGGER.warning("RemoteException in redoStep()");
                }
            }
        }
        return true;
    }


    @Override
    public void getGlobalState(int sender, int hand_cnt, int howManyPicked, UnoCard card, int direction, int turnOfPlayer){
        vectorClock[sender]=hand_cnt;
        tmp_hand_cnt=hand_cnt;
        unoDeck.setHowManyPicked(0);
        unoDeck.removeCardFromDeck(howManyPicked);
        unoDeck.setLastDiscardedCard(card);
        UnoRules.setDirection(direction);
//        LOGGER.info("RemovedFromOther:"+howManyPicked);
//        LOGGER.info("Direction: "+direction);
//        LOGGER.info("Played Card: "+card.getCardID());
        //update GUI
        if (callbackObject != null){
            callbackObject.setDiscardedDeckFront(card.getCardID());
            callbackObject.setTurnLabel("Player "+turnOfPlayer);
        }
        if (hasGameToken) {
            gameTimer = new Timer();
            gameTimer.schedule(new GameTimerThread(), gTimeout);
        }
    }

    @Override
    public void setGlobalState(UnoCard card, int direction) throws RemoteException{
        int hand_cnt = tmp_hand_cnt+1;
        int pickedCnt=unoDeck.getHowManyPicked();
        for(Integer peerID: remotePeerHashMap.keySet()){
            try{
                if(peerID!=this.ID)
                    remotePeerHashMap.get(peerID).getGlobalState(this.ID, hand_cnt,pickedCnt, card, direction, turnOfPlayer);
            }catch (RemoteException e){
                e.printStackTrace();
            }
        }
    }

    public void initialHand(){
        for (int i=0; i<ID; i++){
            unoPlayer.drawInitialHand(unoDeck);
            unoPlayer.emptyHand();
        }
        unoPlayer.drawInitialHand(unoDeck);
    }

    public void killGameTimer(){
        if (gameTimer != null) {
            gameTimer.cancel();
            gameTimer = null;
        }
    }

    public void killFTTimer(){
        if (ftTimer != null) {
            ftTimer.cancel();
            ftTimer = null;
        }
    }


    public class FaultToleranceThread extends TimerTask{

         public void run(){
             ftTokenRecvLock.lock();
             if(ftTokenRecv) {
                 //internal ACK
                 ftTokenRecv = false;
                 ftTokenRecvLock.unlock();
//                 LOGGER.info("FT_Thread: Token received");
             }
             else {
                 ftTokenRecvLock.unlock();
                 LOGGER.info("Failure detected, token not received in "+ftTimeout+"ms");
                 LOGGER.info("starting recovery procedure");
                 killFTTimer();
                 if( !recoveryProcedure() ){
                     LOGGER.warning("Recovery did not complete");
                     LOGGER.warning("Terminating tokenPasser thread");
                     ftTokenPasserThread.interrupt();
                     killGameTimer();
                 }else {
                     LOGGER.info("Recovery completed");
                     //recreate the ft token
                     getFTToken();
                 }
             }
         }
    }

    public class FTTokenPasserThread extends Thread{
        public ReentrantLock lock;
        public Condition recvdFTToken;

        public FTTokenPasserThread(){
            lock = new ReentrantLock();
            recvdFTToken = lock.newCondition();
        }

        public void run(){
            while(true){
                try {
                    lock.lock();
                    while (!hasFTToken)
                        recvdFTToken.await();
                    //DO PASSING
                    try{
                        Thread.sleep(tokenHoldTime);
                    }catch (InterruptedException e){
                        LOGGER.info("Sleep interrupted");
                    }
                    if( !passFTToken() ){
                        killGameTimer();
                        return;
                    }
                }catch (InterruptedException e){
                    killGameTimer();
                    LOGGER.info("Thread terminated");
                }
                finally {
                    lock.unlock();
                }
            }
        }

        public boolean passFTToken(){
            int nextPeer = getNextInRing(FT_RING_DIRECTION);
            while(nextPeer != -1 ){
                try {
                    //Pass token
                    remotePeerHashMap.get(nextPeer).getFTToken();
//                    LOGGER.info("FTToken passed");
                    lock.lock();
                    hasFTToken = false;
                    lock.unlock();
                    //Launch Fault tolerance timeout
                    killFTTimer();
                    scheduleFTTimer(ftTimeout);
                    return true;
                }catch (RemoteException e){
                    killFTTimer();
                    LOGGER.warning("Communication failed the next peer in the ring is down");
                    LOGGER.info("starting recovery procedure");
                    if( !recoveryProcedure() ){
                        return false;
                    }
                    scheduleFTTimer(ftTimeout);
                    LOGGER.info("Recovery completed");
                }
                nextPeer = getNextInRing(FT_RING_DIRECTION);
            }
            LOGGER.info("Empty ring");
            return false;
        }
    }

    public class GameTimerThread extends TimerTask{

        public void run(){
            LOGGER.info("Hand timeout elapsed! Default step");
            callbackObject.addCard(unoPlayer.getCardfromDeck(unoDeck));
            callbackObject.disallowDrawing();
            callbackObject.disallowPlaying();
            callbackObject.clearEventLabel();
            try {
                sendGameToken();
            } catch (RemoteException e) {
                LOGGER.warning("sendGameToken() failed");
            }
        }
    }

}
