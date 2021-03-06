package opendct.video.rtsp.rtcp;

import opendct.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Incomplete implementation. This will only listen and partly validate packets.
 */
public class RTCPClient implements Runnable {
    private final Logger logger = LogManager.getLogger(RTCPClient.class);

    public static final int RTCP_SR = 200;
    public static final int RTCP_RR = 201;
    public static final int RTCP_SDES = 202;
    public static final int RTCP_BYE = 203;
    public static final int RTCP_APP = 204;

    public static final byte RTCP_SDES_END = 0;
    public static final byte RTCP_SDES_CNAME = 1;
    public static final byte RTCP_SDES_NAME = 2;
    public static final byte RTCP_SDES_EMAIL = 3;
    public static final byte RTCP_SDES_PHONE = 4;
    public static final byte RTCP_SDES_LOC = 5;
    public static final byte RTCP_SDES_TOOL = 6;
    public static final byte RTCP_SDES_NOTE = 7;
    public static final byte RTCP_SDES_PRIV = 8;

    private final int udpReceiveBufferSize =
            Config.getInteger("producer.nio.udp_receive_buffer", 13280);

    private DatagramChannel datagramChannel = null;
    private InetAddress rtcpRemoteIP;
    private int rtcpLocalPort;

    private Thread rtcpClientThread = null;

    AtomicBoolean running = new AtomicBoolean(false);
    AtomicBoolean stop = new AtomicBoolean(false);

    /**
     * Opens the requested port for listening and starts processing RTCP packets.
     *
     * @param rtcpRemoteIP  This is the remote IP address to listen for UDP traffic from.
     * @param rtcpLocalPort This is the local UDP port to be used to listen for RTCP traffic.
     * @throws IOException Thrown if the port is unable to be opened.
     */
    public void startReceiving(InetAddress rtcpRemoteIP, int rtcpLocalPort) throws IOException {
        logger.entry(rtcpRemoteIP, rtcpLocalPort);

        if (running.getAndSet(true)) {
            logger.debug("RTCP client is already running.");
            return;
        }

        this.rtcpLocalPort = rtcpLocalPort;
        this.rtcpRemoteIP = rtcpRemoteIP;

        try {
            datagramChannel = DatagramChannel.open();
            datagramChannel.socket().bind(new InetSocketAddress(this.rtcpLocalPort));
            datagramChannel.socket().setBroadcast(false);
            datagramChannel.socket().setReceiveBufferSize(udpReceiveBufferSize);

            // In case 0 was used and a port was automatically chosen.
            this.rtcpLocalPort = datagramChannel.socket().getLocalPort();
        } catch (IOException e) {
            if (datagramChannel != null && datagramChannel.isConnected()) {
                try {
                    datagramChannel.close();
                    datagramChannel.socket().close();
                } catch (IOException e0) {
                    logger.debug("Producer created an exception while closing the datagram channel => {}", e0.toString());
                }
            }
            throw e;
        }

        stop.set(false);
        rtcpClientThread = new Thread(this);
        rtcpClientThread.setName("RTCPClient-" + rtcpClientThread.getId());
        rtcpClientThread.start();

        logger.exit();
    }

    public void stopReceiving() {
        stop.set(true);

        if (rtcpClientThread != null) {
            rtcpClientThread.interrupt();
        }

        if (datagramChannel != null) {
            try {
                datagramChannel.close();
                datagramChannel.socket().close();
            } catch (IOException e) {
                logger.debug("RTCP client created an exception while closing the datagram channel => {}", e.getMessage());
            }
        }
    }

    public void waitForStop() throws InterruptedException {

        int timeout = 0;

        while (rtcpClientThread != null && rtcpClientThread.isAlive()) {
            rtcpClientThread.interrupt();
            rtcpClientThread.join(1000);

            if (timeout++ > 5) {
                logger.warn("RTCP client has been waiting for its thread to stop for over {} seconds.", timeout);
            }
        }
    }

    public void run() {
        logger.info("RTCP client thread is running.");

        // A standard RTP transmitted datagram payload should not be larger than 1328 bytes.
        ByteBuffer datagramBuffer = ByteBuffer.allocateDirect(1500);
        ByteBuffer responseBuffer = ByteBuffer.allocateDirect(1500);
        int datagramSize;

        while (!stop.get() && !Thread.currentThread().isInterrupted()) {
            try {
                logger.debug("Waiting for RTCP datagram...");
                SocketAddress socketAddress = datagramChannel.receive(datagramBuffer);
                datagramSize = datagramBuffer.position();
                datagramBuffer.flip();

                logger.debug("Received an RTCP packet with the size {}.", datagramSize);

                try {
                    responseBuffer = processRTCP(datagramBuffer, responseBuffer);

                    // If the packet was valid and we have a response, send it.
                    if (responseBuffer.remaining() > 0) {
                        datagramChannel.send(responseBuffer, socketAddress);
                        responseBuffer.clear();
                    }
                } catch (Exception e) {
                    logger.error("An unexpected error happened while processing the RTCP datagram => ", e);
                }

                datagramBuffer.clear();
            } catch (IOException e) {
                if (!stop.get()) {
                    logger.error("RTCP port {} has closed unexpectedly. Attempting to re-open => ", rtcpLocalPort, e);

                    if (datagramChannel != null && datagramChannel.isConnected()) {
                        try {
                            datagramChannel.close();
                            datagramChannel.socket().close();
                        } catch (IOException e0) {
                            logger.debug("RTCP client created an exception while closing the datagram channel => {}", e0.toString());
                        }
                    }

                    running.set(false);

                    try {
                        startReceiving(rtcpRemoteIP, rtcpLocalPort);
                    } catch (IOException e0) {
                        logger.debug("RTCP client is unable to re-open the port {} => ", rtcpLocalPort, e0);
                    }

                    // This starts a new listening thread, so we need to completely exit this thread.
                    return;
                } else {
                    logger.debug("The RTCP client thread has been requested to stop => {}", e.getMessage());
                    break;
                }
            } catch (Exception e) {
                logger.error("The RTCP thread has experienced an unexpected exception => ", e);
                break;
            }
        }

        if (datagramChannel != null && datagramChannel.isConnected()) {
            try {
                datagramChannel.close();
                datagramChannel.socket().close();
            } catch (IOException e) {
                logger.debug("RTCP client created an exception while closing the datagram channel => {}", e.getMessage());
            }
        }

        stop.set(false);
        running.set(false);

        logger.info("RTCP client thread is stopped.");
    }

    /**
     * Processes the contents of an RTCP packet, then returns the same buffer containing a response.
     *
     * @param datagram The RTCP datagram.
     * @param response The response for the datagram.
     * @return RTCP response.
     */
    public ByteBuffer processRTCP(ByteBuffer datagram, ByteBuffer response) {

        while (datagram.remaining() >= 8) {
            int packetLoss;
            long reporterSsrc = 0;

            int b0 = datagram.get() & 0xff;

            int version = b0 & 0xc0;
            if (version != 2) {
                logger.error("RTCP packet is not version 2: {}.", version);
                response.clear();
                return response;
            }

            int paddingBit = b0 & 0x20;
            if (paddingBit != 0) {
                logger.error("RTCP padding bit is not zero: {}.", paddingBit);
                response.clear();
                return response;
            }

            int itemCount = b0 & 0x1f;

            int packetType = datagram.get() & 0xff;

            int payloadLength = datagram.getShort() & 0xffff;

            int itemCounter = itemCount;

            switch (packetType) {
                case RTCP_RR:
                    long reporteeSsrc = 0;
                    int lossFraction = 0;
                    int numberOfPacketsLost = 0;
                    long highestSequenceNumberReceived = 0;
                    long interarrivalJitter = 0;
                    long lsr = 0;
                    long dlsr = 0;


                    // This report block always exists in this packet type.
                    reporterSsrc = datagram.getInt() & 0xffffffff;

                    if (itemCounter-- > 0) {
                        reporteeSsrc = datagram.getInt() & 0xffffffff;

                        if (itemCounter-- > 0) {
                            packetLoss = datagram.getInt() & 0xffffffff;
                            lossFraction = (packetLoss & 0xff000000) >> 6;
                            numberOfPacketsLost = packetLoss & 0x00ffffff;
                        }

                        if (itemCounter-- > 0) {
                            highestSequenceNumberReceived = datagram.getInt() & 0xffffffff;
                        }

                        if (itemCounter-- > 0) {
                            interarrivalJitter = datagram.getInt() & 0xffffffff;
                        }

                        if (itemCounter-- > 0) {
                            lsr = datagram.getInt() & 0xffffffff;
                        }

                        if (itemCounter-- > 0) {
                            dlsr = datagram.getInt() & 0xffffffff;
                        }
                    }

                    logger.debug("itemCount = {}, reporterSsrc = {}, reporteeSsrc = {}, lossFraction = {}, numberOfPacketsLost = {}, highestSequenceNumberReceived = {}, interarrivalJitter = {}, lsr = {}, dlsr = {}",
                            itemCount, reporterSsrc, reporteeSsrc, lossFraction, numberOfPacketsLost, highestSequenceNumberReceived, interarrivalJitter, lsr, dlsr);

                    break;
                case RTCP_SR:
                    long ntpTimestamp = 0;
                    int rtpTimestamp = 0;
                    int senderPacketCount = 0;
                    int senderOctetCount = 0;

                    if (itemCounter-- > 0) {
                        reporterSsrc = datagram.getInt();

                        if (itemCounter-- > 0) {
                            ntpTimestamp = datagram.getLong();
                        }

                        if (itemCount-- > 0) {
                            rtpTimestamp =  datagram.getInt();
                        }

                        if (itemCount-- > 0) {
                            senderPacketCount =  datagram.getInt();
                        }

                        if (itemCount-- > 0) {
                            senderOctetCount =  datagram.getInt();
                        }
                    }

                    logger.debug("itemCount = {}, reporterSsrc = {}, ntpTimestamp = {}, rtpTimestamp = {}, senderPacketCount = {}, senderOctetCount = {}",
                            itemCount, reporterSsrc, ntpTimestamp, rtpTimestamp, senderPacketCount, senderOctetCount);

                    break;
                case RTCP_SDES:
                    datagram.position(datagram.position() + payloadLength);
                    break;
                case RTCP_BYE:
                    datagram.position(datagram.position() + payloadLength);
                    break;
                case RTCP_APP:
                    datagram.position(datagram.position() + payloadLength);
                    break;
                default:
                    logger.error("Unsupported RTCP packet type: {}", packetType);
            }
        }

        response.clear();
        //response.flip();
        return response;
    }

    public static ByteBuffer writeHeader(ByteBuffer buffer, int version, int padding, int itemCount, int packetType, int packetLength) {
        byte b0 = 0;
        b0 |= ((version & 0xff) << 6);
        b0 |= ((padding & 0xff) << 5);
        b0 |= itemCount & 0xff;

        buffer.put(b0);
        buffer.put((byte)(packetType & 0xff));
        buffer.putShort((short)(packetLength & 0xffff));

        return buffer;
    }
}
