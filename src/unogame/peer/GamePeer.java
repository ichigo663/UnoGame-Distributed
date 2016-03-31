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
import unogame.game.*;
import unogame.gui.GUITable;

public class GamePeer implements RemotePeer{
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
    private UnoPlayer unoPlayer;
    private UnoDeck unoDeck;

    private static final String RMI_OBJ_NAME = "RemotePeer";
    private static final int RMI_PORT = 1099;
    private static final int FT_RING_DIRECTION = 1;

    private int ftTimeout; //in ms
    private int tokenHoldTime = 1000; //in ms
    private int expectedTransmissionTime = 100; //in ms
    //TODO this is a debug value, change it to something significant
    private static final int gTimeout = 120000; //in ms

    private GUITable callbackObject; //its for updating the turn Label

    public GamePeer(int id){
        this.ID = id;
        hasGameToken = false;
        remotePeerHashMap = new HashMap<>();
        vectorClock= new int[8];
        initRMIServer();
        initFT();
    }

    public GamePeer(int id, boolean hasGameToken, boolean hasFTToken, UnoPlayer unoPlayer, UnoDeck unoDeck){
        this.ID = id;
        this.hasGameToken = hasGameToken;
        this.hasFTToken = hasFTToken;
        this.unoPlayer = unoPlayer;
        this.unoDeck = unoDeck;
        remotePeerHashMap = new HashMap<>();
        vectorClock= new int[8];
        //System.out.println("ID"+this.ID+":"+vectorClock[this.ID-1]);
        initRMIServer();
        initFT();
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

    public void setTokenHoldTime(int tokenHoldTime) {
        this.tokenHoldTime = tokenHoldTime;
        updateFTTimeout();
    }

    public void setExpectedTransmissionTime(int expectedTransmissionTime) {
        this.expectedTransmissionTime = expectedTransmissionTime;
        updateFTTimeout();
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
            System.out.println(RMI_OBJ_NAME+" bound");
        } catch (Exception e) {
            System.err.println(RMI_OBJ_NAME+":");
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
        System.out.println(addr + " added!");
    }

    //RemotePeer Interface implementation
    @Override
    public int getID(){
        return this.ID;
    }

    @Override
    public void getGameToken(int cardsToPick){
        hasGameToken = true;
        SpecialType lastCardType = unoDeck.getLastDiscardedCard().getType();
        //check if you have received a PLUS card and if you can contest it
        unoPlayer.setCardsToPick(cardsToPick);
        //update GUI
        if (callbackObject != null) {
            callbackObject.allowDrawing();
            callbackObject.allowPlaying();
            callbackObject.setTurnLabel("Your Turn");
            if (unoPlayer.getCardsToPick() > 0){
                callbackObject.setPlusEventLabel(cardsToPick);
            }
            else
                callbackObject.clearPlusEventLabel();
        }
        if( UnoRules.isSpecialPlayable(unoPlayer.getHand()) ) {
            unoPlayer.setRecvSpecial(true);
        } else {
            for(int i = 0; i < cardsToPick; i++)
                callbackObject.addCard(unoPlayer.getCardfromDeck(unoDeck));
            unoPlayer.setCardsToPick(0);
        }
        System.out.println("\n\n\n\n\nID" + this.ID + " : Game token received!");
    }

    public void sendGameToken() throws RemoteException{
        if(hasGameToken){
            killGameTimer();
            //check if you have not contested a PLUS card
            //in that case draw the correct amount from the deck
            if( unoPlayer.hasRecvSpecial() && ( !unoPlayer.hasPlayedCard() || !unoDeck.getLastDiscardedCard().isPlus() ) ){
                for(int i = 0; i < unoPlayer.getCardsToPick(); i++)
                    callbackObject.addCard(unoPlayer.getCardfromDeck(unoDeck));
            }
            unoPlayer.setRecvSpecial(false);
            int peerID=getNextInRing(UnoRules.getDirection());
            if( peerID != -1) {
                vectorClock[this.ID - 1] = tmp_hand_cnt + 1;
                System.out.println("ID" + this.ID + " : Event" + vectorClock[this.ID - 1]);
                setGlobalState(unoDeck.getLastDiscardedCard(), UnoRules.getDirection());
                if( unoDeck.getLastDiscardedCard().getType() == SpecialType.REVERSE && remotePeerHashMap.size() == 1 ){
                    getGameToken(unoPlayer.getCardsToPick());
                }else {
                    hasGameToken = false;
                    if( unoPlayer.hasPlayedCard() && unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS2 )
                        remotePeerHashMap.get(peerID).getGameToken(unoPlayer.getCardsToPick()+2);
                    else if( unoPlayer.hasPlayedCard() && unoDeck.getLastDiscardedCard().getType() == SpecialType.PLUS4 )
                        remotePeerHashMap.get(peerID).getGameToken(unoPlayer.getCardsToPick()+4);
                    else
                        remotePeerHashMap.get(peerID).getGameToken(0);
                }
            }else
                System.err.println("sendGameToken(): no other peer in game");
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
        //System.out.println("FTToken received");
    }

    @Override
    public void addPlayers(HashMap<Integer, String> peers){
        for (Integer key: peers.keySet()){
            try {
                if (!remotePeerHashMap.containsKey(key))
                    addRemotePeer(peers.get(key));
            }catch(NotBoundException e){
                System.err.println("addPlayers(): Player at "+peers.get(key)+" not bound");
            }catch (RemoteException e){
                System.err.println("addPlayers(): Failed communication with player at "+peers.get(key));
            }
        }
    }

    @Override
    public void reconfigureRing(ArrayList<Integer> crashedPeers){
        //reconfigure the logical ring
        System.out.println("reconfigureRing(): reconfiguring the ring");
        for(Integer peerID: crashedPeers){
            remotePeerHashMap.remove(peerID);
        }
    }

    //isAlive procedure in a challenge&response way
    //the process to be considered alive and correct must answer
    //with the correct size of the ring
    //in addition to what described above, it has the role
    //of cancelling any pending timer and setting a new one in case
    //the process doing the recovery procedure fails as well
    @Override
    public int isAlive(int ringSize){
        //cancel scheduled local failure detector
        System.out.println("isAlive(): stopping local failure detector");
        System.out.println("isAlive(): starting recovery timeout");
        scheduleFTTimer(ftTimeout+(ftTimeout*ID));
        return remotePeerHashMap.size();
    }

    @Override
    public boolean hasGToken(){
        return hasGameToken;
    }

    private void scheduleFTTimer(int timeout){
        ftTimer.cancel();
        ftTimer = null;
        ftTimer = new Timer();
        ftTimer.schedule(new FaultToleranceThread(), timeout);
    }


    //get the next peerID the ring
    //if it return -1 it means there are no neighbours
    private int getNextInRing(int direction){
        int peers = remotePeerHashMap.size()+1;
        try {
            for (int i = getID()+direction; i != getID(); i = i+direction) {
                if(i>peers || i < 0)
                    i = 1;
                else if(i==0)
                    i = peers;
                if (remotePeerHashMap.containsKey(i))
                    return i;
            }
        }catch (ArithmeticException e){
            System.out.println("getNextInRing(): no other peers in the ring");
            return -1;
        }
        return -1;
    }

    //used when either the FTToken is lost
    //or when in passing the token onwards
    //we detect a crashed peer
    private boolean recoveryProcedure(){
        ArrayList<Integer> crashedPeers = new ArrayList<>();
        boolean gameTokenLost = false;
        //discover who has crashed
        for(Integer peerID: remotePeerHashMap.keySet()){
            try{
                int ringSize = remotePeerHashMap.size();
                if( remotePeerHashMap.get(peerID).isAlive(ringSize) != ringSize ) {
                    System.out.println("recoveryProcedure(): Peer "+peerID+" is alive, but wrong answer");
                    crashedPeers.add(peerID);
                }
                //check if the GameToken got lost
                if( remotePeerHashMap.get(peerID).hasGToken() )
                    gameTokenLost = true;
            }catch (RemoteException e){
                System.out.println("recoveryProcedure(): Peer "+peerID+" is down, adding to list of crashed peers");
                crashedPeers.add(peerID);
            }
        }
        //reconfigure the logical ring
        if(crashedPeers.size() > 0) {
            for (Integer peerID : crashedPeers) {
                System.out.println("recoveryProcedure(): reconfiguring the logical ring");
                remotePeerHashMap.remove(peerID);
            }
        }
        if(remotePeerHashMap.size() == 0){
            System.out.println("recoveryProcedure(): The ring is empty");
            return false;
        }
        //communicate the other peers to reconfigure as well
        if(crashedPeers.size() > 0) {
            for (Integer peerID : remotePeerHashMap.keySet()) {
                try {
                    remotePeerHashMap.get(peerID).reconfigureRing(crashedPeers);
                } catch (RemoteException e) {
                    System.out.println("recoveryProcedure(): Peer " + peerID + " is down, the ring will be eventually reconfigured");
                }
            }
        }
        //TODO recreate GameToken and infer who's next based on the peers vector clocks
        return true;
    }


    @Override
    public void getGlobalState(int sender, int hand_cnt, int howManyPicked, UnoCard card, int direction){
        vectorClock[sender-1]=hand_cnt;
        tmp_hand_cnt=hand_cnt;
        unoDeck.setHowManyPicked(0);
        unoDeck.removeCardFromDeck(howManyPicked);
        unoDeck.setLastDiscardedCard(card);
        UnoRules.setDirection(direction);
        System.out.println("RemovedFromOther:"+howManyPicked);
        System.out.println("Direction: "+direction);
        System.out.println("Played Card: "+card.getCardID());
        //update GUI
        if (callbackObject != null && card != null){
            callbackObject.setDiscardedDeckFront(card.getCardID());
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
                    remotePeerHashMap.get(peerID).getGlobalState(this.ID, hand_cnt,pickedCnt, card, direction);
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


    public class FaultToleranceThread extends TimerTask{

         public void run(){
             ftTokenRecvLock.lock();
             if(ftTokenRecv) {
                 //internal ACK
                 ftTokenRecv = false;
                 ftTokenRecvLock.unlock();
                 //System.out.println("FT_Thread: Token received");
             }
             else {
                 ftTokenRecvLock.unlock();
                 System.out.println("FT_Thread: Failure detected, token not received in "+ftTimeout+"ms");
                 System.out.println("FT_Thread: starting recovery procedure");
                 if( !recoveryProcedure() ){
                     ftTokenPasserThread.interrupt();
                     System.out.println("FT_Thread: Recovery did not complete");
                     System.out.println("FT_Thread: Terminating tokenPasser thread");
                 }else
                     System.out.println("FT_Thread: Recovery completed");
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
                        //Simulating processing of token
                        Thread.sleep(tokenHoldTime);
                    }catch (InterruptedException e){System.out.println("Sleep interrupted");}
                    if( !passFTToken() ){
                        System.out.println("FTTokenPasserThread: Thread terminated");
                        return;
                    }
                }catch (InterruptedException e){}
                finally {
                    lock.unlock();
                }
            }
        }

        private boolean passFTToken(){
            int nextPeer = getNextInRing(FT_RING_DIRECTION);
            while(nextPeer != -1 ){
                try {
                    //Pass token
                    remotePeerHashMap.get(nextPeer).getFTToken();
                    //System.out.println("FTToken passed");
                    lock.lock();
                    hasFTToken = false;
                    lock.unlock();
                    //Launch Fault tolerance timeout
                    scheduleFTTimer(ftTimeout);
                    return true;
                }catch (RemoteException e){
                    System.out.println("passFTToken(): Communication failed the next peer in the ring is down");
                    System.out.println("passFTToken(): starting recovery procedure");
                    if( !recoveryProcedure() ){
                        return false;
                    }
                }
                nextPeer = getNextInRing(FT_RING_DIRECTION);
            }
            System.out.println("passFTToken(): Empty ring");
            return false;
        }
    }

    public class GameTimerThread extends TimerTask{

        public void run(){

            //Sei un coglione che hai fatto scadere il tempo
            //beccati sta carta, e neanche la butti così la prossima volta ti sbrighi
            System.out.println("Hand timeout elapsed! Default step");
            unoPlayer.getCardfromDeck(unoDeck);
            try {
                sendGameToken();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

}
