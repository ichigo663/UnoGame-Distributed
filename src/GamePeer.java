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

public class GamePeer implements RemotePeer{
    public HashMap<Integer, RemotePeer> remotePeerHashMap;
    public ReentrantLock ftTokenRecvLock;
    public boolean ftTokenRecv;

    private boolean hasGameToken;
    private boolean hasFTToken;
    private int ID;
    private Timer ftTimer;
    private FTTokenPasserThread ftTokenPasserThread;

    private static final String RMI_OBJ_NAME = "RemotePeer";
    private static final int FT_TIMEOUT = 3000; //in ms
    private static final int FT_RING_DIRECTION = 1;

    public GamePeer(int id){
        this.ID = id;
        hasGameToken = false;
        remotePeerHashMap = new HashMap<>();
        initRMIServer();
        initFT();
    }

    public GamePeer(int id, boolean hasGameToken, boolean hasFTToken){
        this.ID = id;
        this.hasGameToken = hasGameToken;
        this.hasFTToken = hasFTToken;
        remotePeerHashMap = new HashMap<>();
        initRMIServer();
        initFT();
    }

    private void initFT(){
        ftTimer = new Timer();
        ftTokenPasserThread = new FTTokenPasserThread();
        ftTokenRecvLock = new ReentrantLock();
        ftTokenRecv = false;
    }

    private void initRMIServer(){
        if (System.getSecurityManager() == null) {
            System.setSecurityManager(new SecurityManager());
        }
        try {
            RemotePeer stub = (RemotePeer) UnicastRemoteObject.exportObject(this, 0);
            Registry registry = LocateRegistry.getRegistry();
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
    }

    public void sendGameToken(int peerID) throws RemoteException{
        if(hasGameToken){
            hasGameToken = false;
            remotePeerHashMap.get(peerID).getGameToken();
        }
    }

    //RemotePeer Interface implementation
    public int getID(){
        return this.ID;
    }

    public void getGameToken(){
        hasGameToken = true;
        System.out.println("ID: "+this.ID+" Game token received!");
    }

    public void getFTToken(){
        ftTokenRecvLock.lock();
        ftTokenRecv = true;
        ftTokenRecvLock.unlock();
        try {
            ftTokenPasserThread.lock.lock();
            hasFTToken = true;
            ftTokenPasserThread.recvdFTToken.signal();
        }finally {
            ftTokenPasserThread.lock.unlock();
        }
    }

    public void reconfigureRing(ArrayList<Integer> crashedPeers){
        //reconfigure the logical ring
        for(Integer peerID: crashedPeers){
            remotePeerHashMap.remove(peerID);
        }
    }

    //isAlive procedure in a challenge&response way
    //the process to be considered alive and correct must answer
    //with the correct size of the ring
    public int isAlive(int ringSize){
        //cancel scheduled local failure detector
        ftTimer.cancel();
        ftTimer = null;
        return remotePeerHashMap.size();
    }

    public boolean hasGToken(){
        return hasGameToken;
    }

    public void restartFaultToleranceThread(){
        ftTimer = new Timer();
    }

    //get the next peerID the ring
    //if it return -1 it means there are no neighbours
    public int getNextInRing(int direction){
        int peersNumber = remotePeerHashMap.size();
        try {
            for (int i = getID() + direction; i != getID(); i = (i + direction) % peersNumber) {
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
    private void recoveryProcedure(){
        ArrayList<Integer> crashedPeers = new ArrayList<>();
        boolean gameTokenLost = false;
        //discover who has crashed
        for(Integer peerID: remotePeerHashMap.keySet()){
            try{
                int ringSize = remotePeerHashMap.size();
                if( remotePeerHashMap.get(peerID).isAlive(ringSize) != ringSize )
                    crashedPeers.add(peerID);
                //check if the GameToken got lost
                if( remotePeerHashMap.get(peerID).hasGToken() )
                    gameTokenLost = true;
            }catch (RemoteException e){
                crashedPeers.add(peerID);
            }
        }
        //reconfigure the logical ring
        for(Integer peerID: crashedPeers){
            remotePeerHashMap.remove(peerID);
        }
        //communicate the other peers to reconfigure as well
        for(Integer peerID: remotePeerHashMap.keySet()){
            try{
                remotePeerHashMap.get(peerID).reconfigureRing(crashedPeers);
            }catch (RemoteException e){
                System.out.println("Peer "+peerID+" is down, the ring will be eventually reconfigured");
            }
        }
        //TODO recreate GameToken and infer who's next based on the peers vector clocks
    }

    public class FaultToleranceThread extends TimerTask{

         public void run(){
             ftTokenRecvLock.lock();
             if(ftTokenRecv) {
                 //internal ACK
                 ftTokenRecv = false;
                 ftTokenRecvLock.unlock();
             }
             else {
                 ftTokenRecvLock.unlock();
                 System.out.println("FT_Thread: Failure detected, token not received in "+FT_TIMEOUT+"ms");
                 System.out.println("passFTToken(): starting recovery procedure");
                 recoveryProcedure();
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
                        Thread.sleep(1000);
                    }catch (InterruptedException e){System.out.println("Sleep interrupted");}
                    passFTToken();
                }catch (InterruptedException e){
                    e.printStackTrace();
                }finally {
                    lock.unlock();
                }
            }
        }

        private void passFTToken(){
            hasFTToken = false;
            int nextPeer = getNextInRing(FT_RING_DIRECTION);
            while(nextPeer != -1 ){
                try {
                    //Pass token
                    remotePeerHashMap.get(nextPeer).getFTToken();
                    //Launch Fault tolerance timeout
                    if(ftTimer == null)
                        ftTimer = new Timer();
                    ftTimer.schedule(new FaultToleranceThread(), FT_TIMEOUT);
                    break;
                }catch (RemoteException e){
                    System.out.println("passFTToken(): Communication failed the next peer in the ring is down");
                    System.out.println("passFTToken(): starting recovery procedure");
                    recoveryProcedure();
                }
                nextPeer = getNextInRing(FT_RING_DIRECTION);
            }
        }

    }

}
