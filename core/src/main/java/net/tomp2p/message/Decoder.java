package net.tomp2p.message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.TreeMap;

import net.tomp2p.connection.SignatureFactory;
import net.tomp2p.connection.TimeoutFactory;
import net.tomp2p.message.Message.Content;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.SimpleBloomFilter;
import net.tomp2p.storage.Data;
import net.tomp2p.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Decoder {

    public static final AttributeKey<InetSocketAddress> INET_ADDRESS_KEY = AttributeKey.valueOf("inet-addr");
    public static final AttributeKey<PeerAddress> PEER_ADDRESS_KEY = AttributeKey.valueOf("peer-addr");

    private static final Logger LOG = LoggerFactory.getLogger(Decoder.class);

    private final Queue<Content> contentTypes = new LinkedList<Message.Content>();

    // private Message2 result = null;

    // current state - needs to be deleted if we want to reuse
    private Message message = null;

    private int neighborSize = -1;
    private NeighborSet neighborSet = null;

    private int keyCollectionSize = -1;
    private KeyCollection keyCollection = null;

    private int mapsSize = -1;
    private DataMap dataMap = null;
    private Data data = null;

    private int keyMap480Size = -1;
    private KeyMap640 keyMap480 = null;

    private int keyMapByteSize = -1;
    private KeyMapByte keyMapByte = null;

    private int bufferSize = -1;
    private Buffer buffer = null;

    private int trackerDataSize = -1;
    private TrackerData trackerData = null;
    private Data currentTrackerData = null;

    private Content lastContent = null;

    private final SignatureFactory signatureFactory;

    public Decoder(SignatureFactory signatureFactory) {
        this.signatureFactory = signatureFactory;
    }

    public boolean decode(ChannelHandlerContext ctx, final ByteBuf buf, InetSocketAddress recipient,
            final InetSocketAddress sender) {

        LOG.debug("decode of TomP2P starts now");

        // store positions for the verification
        ByteBuffer[] byteBuffers = buf.nioBuffers();
        int len = byteBuffers.length;
        int[] pos = new int[len];
        for (int i = 0; i < len; i++) {
            pos[i] = byteBuffers[i].position();
        }

        try {
            // set the sender of this message for handling timeout
            final Attribute<InetSocketAddress> attributeInet = ctx.attr(INET_ADDRESS_KEY);
            attributeInet.set(sender);

            if (message == null) {
                boolean doneHeader = decodeHeader(buf, recipient, sender);
                if (doneHeader) {
                    // store the sender as an attribute
                    final Attribute<PeerAddress> attributePeerAddress = ctx.attr(PEER_ADDRESS_KEY);
                    attributePeerAddress.set(message.getSender());
                    message.udp(ctx.channel() instanceof DatagramChannel);
                    if (message.isFireAndForget() && message.isUdp()) {
                        TimeoutFactory.removeTimeout(ctx);
                    }
                } else {
                    return false;
                }
            }

            boolean donePayload = decodePayload(buf);
            verifySignature(byteBuffers, pos, donePayload);
            // see https://github.com/netty/netty/issues/1976 (TODO: enable again in 4.0.13)
            // TODO: not sure if we can use discard here if we want to keep data in the Data object
            //buf.discardSomeReadBytes();
            return donePayload;

        } catch (Exception e) {
            ctx.fireExceptionCaught(e);
            e.printStackTrace();
            return true;
        }
    }

    private void verifySignature(final ByteBuffer[] byteBuffers, final int[] pos, final boolean donePayload)
            throws SignatureException, IOException {
        final int len = byteBuffers.length;
        if (message.isSign()) {
            for (int i = 0; i < len; i++) {
                // since we read the bytebuffer, the nio buffer also has a
                byteBuffers[i].position(pos[i]);
                if (donePayload && i + 1 == len) {
                    byteBuffers[i].limit(byteBuffers[i].limit()
                            - (Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE));
                }
                message.signatureForVerification().update(byteBuffers[i]);
            }

            if (donePayload) {
                byte[] signatureReceived = message.receivedSignature().encode();
                if (message.signatureForVerification().verify(signatureReceived)) {
                    // set public key only if signature is correct
                    message.setPublicKey(message.receivedPublicKey());
                    LOG.debug("signature check ok");
                } else {
                    LOG.debug("wrong signature!");
                }
            }
        }
    }

    public boolean decodeHeader(final ByteBuf buf, InetSocketAddress recipient,
            final InetSocketAddress sender) {
        // we don't have the header yet, we need the full header first
        if (message == null) {
            if (buf.readableBytes() < MessageHeaderCodec.HEADER_SIZE) {
                // wait for more data
                return false;
            }
            message = MessageHeaderCodec.decodeHeader(buf, recipient, sender);
            // we have set the content types already
            message.presetContentTypes(true);

            for (Content content : message.getContentTypes()) {
                if (content == Content.EMPTY) {
                    break;
                }
                if (content == Content.PUBLIC_KEY_SIGNATURE) {
                    message.setHintSign();
                }
                contentTypes.offer(content);
            }
            LOG.debug("parsed message {}", message);
            return true;
        }
        return false;
    }

    public boolean decodePayload(final ByteBuf buf) throws NoSuchAlgorithmException, InvalidKeySpecException,
            InvalidKeyException {
        LOG.debug("about to pass message {} to {}", message, message.senderSocket());
        if (!message.hasContent()) {
            return true;
        }

        // payload comes here
        int size;
        while (contentTypes.size() > 0) {
            Content content = contentTypes.peek();
            switch (content) {
            case INTEGER:
                if (buf.readableBytes() < Utils.INTEGER_BYTE_SIZE) {
                    return false;
                }
                message.setInteger(buf.readInt());
                lastContent = contentTypes.poll();
                break;
            case LONG:
                if (buf.readableBytes() < Utils.LONG_BYTE_SIZE) {
                    return false;
                }
                message.setLong(buf.readLong());
                lastContent = contentTypes.poll();
                break;
            case KEY:
                if (buf.readableBytes() < Number160.BYTE_ARRAY_SIZE) {
                    return false;
                }
                byte[] me = new byte[Number160.BYTE_ARRAY_SIZE];
                buf.readBytes(me);
                message.setKey(new Number160(me));
                lastContent = contentTypes.poll();
                break;
            case BLOOM_FILTER:
                if (buf.readableBytes() < Utils.SHORT_BYTE_SIZE) {
                    return false;
                }
                size = buf.getUnsignedShort(buf.readerIndex());
                if (buf.readableBytes() < size) {
                    return false;
                }
                message.setBloomFilter(new SimpleBloomFilter<Number160>(buf));
                lastContent = contentTypes.poll();
                break;
            case SET_NEIGHBORS:
                if (neighborSize == -1 && buf.readableBytes() < Utils.BYTE_SIZE) {
                    return false;
                }
                if (neighborSize == -1) {
                    neighborSize = buf.readUnsignedByte();
                }
                if (neighborSet == null) {
                    neighborSet = new NeighborSet(-1, new ArrayList<PeerAddress>(neighborSize));
                }
                for (int i = neighborSet.size(); i < neighborSize; i++) {
                    int header = buf.getUnsignedShort(buf.readerIndex());
                    size = PeerAddress.size(header);
                    if (buf.readableBytes() < size) {
                        return false;
                    }
                    PeerAddress pa = new PeerAddress(buf);
                    neighborSet.add(pa);
                }
                message.setNeighborsSet(neighborSet);
                lastContent = contentTypes.poll();
                neighborSize = -1;
                neighborSet = null;
                break;
            case SET_KEY640:
                if (keyCollectionSize == -1 && buf.readableBytes() < Utils.INTEGER_BYTE_SIZE) {
                    return false;
                }
                if (keyCollectionSize == -1) {
                    keyCollectionSize = buf.readInt();
                }
                if (keyCollection == null) {
                    keyCollection = new KeyCollection(new ArrayList<Number640>(keyCollectionSize));
                }
                for (int i = keyCollection.size(); i < keyCollectionSize; i++) {
                    if (buf.readableBytes() < Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE
                            + Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE) {
                        return false;
                    }
                    byte[] me2 = new byte[Number160.BYTE_ARRAY_SIZE];
                    buf.readBytes(me2);
                    Number160 locationKey = new Number160(me2);
                    buf.readBytes(me2);
                    Number160 domainKey = new Number160(me2);
                    buf.readBytes(me2);
                    Number160 contentKey = new Number160(me2);
                    buf.readBytes(me2);
                    Number160 versionKey = new Number160(me2);
                    keyCollection.add(new Number640(locationKey, domainKey, contentKey, versionKey));
                }
                message.setKeyCollection(keyCollection);
                lastContent = contentTypes.poll();
                keyCollectionSize = -1;
                keyCollection = null;
                break;
            case MAP_KEY640_DATA:
                if (mapsSize == -1 && buf.readableBytes() < Utils.INTEGER_BYTE_SIZE) {
                    return false;
                }
                if (mapsSize == -1) {
                    mapsSize = buf.readInt();
                }
                if (dataMap == null) {
                    dataMap = new DataMap(new HashMap<Number640, Data>(2 * mapsSize));
                }
                if (data != null) {
                    if (!data.decodeBuffer(buf)) {
                        return false;
                    }
                    if (!data.decodeDone(buf)) {
                        return false;
                    }
                    data = null;
                }
                for (int i = dataMap.size(); i < mapsSize; i++) {
                    if (buf.readableBytes() < Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE
                            + Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE) {
                        return false;
                    }
                    byte[] me3 = new byte[Number160.BYTE_ARRAY_SIZE];
                    buf.readBytes(me3);
                    Number160 locationKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 domainKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 contentKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 versionKey = new Number160(me3);
                    data = Data.decodeHeader(buf);
                    if (data == null) {
                        return false;
                    }
                    dataMap.dataMap()
                            .put(new Number640(locationKey, domainKey, contentKey, versionKey), data);

                    if (message.isSign()) {
                        data.publicKey(message.publicKeyReference());
                    }

                    if (!data.decodeBuffer(buf)) {
                        return false;
                    }
                    if (!data.decodeDone(buf)) {
                        return false;
                    }
                    data = null;

                }

                message.setDataMap(dataMap);
                lastContent = contentTypes.poll();
                mapsSize = -1;
                dataMap = null;
                break;
            case MAP_KEY640_KEY:
                if (keyMap480Size == -1 && buf.readableBytes() < Utils.INTEGER_BYTE_SIZE) {
                    return false;
                }
                if (keyMap480Size == -1) {
                    keyMap480Size = buf.readInt();
                }
                if (keyMap480 == null) {
                    keyMap480 = new KeyMap640(new TreeMap<Number640, Number160>());
                }

                for (int i = keyMap480.size(); i < keyMap480Size; i++) {
                    if (buf.readableBytes() < Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE
                            + Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE
                            + Number160.BYTE_ARRAY_SIZE) {
                        return false;
                    }
                    byte[] me3 = new byte[Number160.BYTE_ARRAY_SIZE];
                    buf.readBytes(me3);
                    Number160 locationKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 domainKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 contentKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 versionKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 valueKey = new Number160(me3);
                    keyMap480.put(new Number640(locationKey, domainKey, contentKey, versionKey), valueKey);
                }

                message.setKeyMap640(keyMap480);
                lastContent = contentTypes.poll();
                keyMap480Size = -1;
                keyMap480 = null;
                break;
            case MAP_KEY640_BYTE:
                if (keyMapByteSize == -1 && buf.readableBytes() < Utils.INTEGER_BYTE_SIZE) {
                    return false;
                }
                if (keyMapByteSize == -1) {
                    keyMapByteSize = buf.readInt();
                }
                if (keyMapByte == null) {
                    keyMapByte = new KeyMapByte(new HashMap<Number640, Byte>(2 * keyMapByteSize));
                }

                for (int i = keyMapByte.size(); i < keyMapByteSize; i++) {
                    if (buf.readableBytes() < Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE
                            + Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE + 1) {
                        return false;
                    }
                    byte[] me3 = new byte[Number160.BYTE_ARRAY_SIZE];
                    buf.readBytes(me3);
                    Number160 locationKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 domainKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 contentKey = new Number160(me3);
                    buf.readBytes(me3);
                    Number160 versionKey = new Number160(me3);
                    byte value = buf.readByte();
                    keyMapByte.put(new Number640(locationKey, domainKey, contentKey, versionKey), value);
                }

                message.setKeyMapByte(keyMapByte);
                lastContent = contentTypes.poll();
                keyMapByteSize = -1;
                keyMapByte = null;
                break;
            case BYTE_BUFFER:
                if (bufferSize == -1 && buf.readableBytes() < Utils.INTEGER_BYTE_SIZE) {
                    return false;
                }
                if (bufferSize == -1) {
                    bufferSize = buf.readInt();
                }
                if (buffer == null) {
                    ByteBuf tmp = Unpooled.compositeBuffer();
                    buffer = new Buffer(tmp, bufferSize);
                }
                int already = buffer.alreadyRead();
                int readable = buf.readableBytes();
                int remaining = bufferSize - already;
                int toread = Math.min(remaining, readable);
                // Unpooled.copiedBuffer(buf.duplicate().writerIndex(writerIndex))
                buffer.addComponent(buf.slice(buf.readerIndex(), toread));
                // slice and addComponent do not modifie the reader or the writer, thus we need to do this on our own
                buf.skipBytes(toread);
                // buffer.buffer().writerIndex(buffer.buffer().writerIndex() + toread);
                // increase writer index
                if (buffer.incRead(toread) != bufferSize) {
                    LOG.debug("we are still looking for data, indicate that we are not finished yet, "
                            + "read = {}, size = {}", buffer.alreadyRead(), bufferSize);
                    return false;
                }
                message.setBuffer(buffer);
                lastContent = contentTypes.poll();
                bufferSize = -1;
                buffer = null;
                break;
            case SET_TRACKER_DATA:
                if (trackerDataSize == -1 && buf.readableBytes() < Utils.BYTE_SIZE) {
                    return false;
                }
                if (trackerDataSize == -1) {
                    trackerDataSize = buf.readUnsignedByte();
                }
                if (trackerData == null) {
                    trackerData = new TrackerData(new HashMap<PeerAddress, Data>(2 * trackerDataSize),
                            message.getSender());
                }
                if (currentTrackerData != null) {
                    if (!currentTrackerData.decodeBuffer(buf)) {
                        return false;
                    }
                    if (!currentTrackerData.decodeDone(buf)) {
                        return false;
                    }
                    currentTrackerData = null;
                }
                for (int i = trackerData.size(); i < trackerDataSize; i++) {

                    if (buf.readableBytes() < Utils.BYTE_SIZE) {
                        return false;
                    }

                    int header = buf.getUnsignedShort(buf.readerIndex());

                    size = PeerAddress.size(header);

                    if (buf.readableBytes() < size) {
                        return false;
                    }
                    PeerAddress pa = new PeerAddress(buf);

                    currentTrackerData = Data.decodeHeader(buf);
                    if (currentTrackerData == null) {
                        return false;
                    }
                    trackerData.map().put(pa, currentTrackerData);
                    if (message.isSign()) {
                        currentTrackerData.publicKey(message.publicKeyReference());
                    }

                    if (!currentTrackerData.decodeBuffer(buf)) {
                        return false;
                    }
                    if (!currentTrackerData.decodeDone(buf)) {
                        return false;
                    }
                    currentTrackerData = null;
                }

                message.setTrackerData(trackerData);
                lastContent = contentTypes.poll();
                trackerDataSize = -1;
                trackerData = null;
                break;

            case PUBLIC_KEY_SIGNATURE:
                if (buf.readableBytes() < 2) {
                    return false;
                }
                int len = buf.getUnsignedShort(buf.readerIndex());

                if (buf.readableBytes() + Utils.SHORT_BYTE_SIZE < len) {
                    return false;
                }
                me = new byte[len];
                buf.skipBytes(2);
                buf.readBytes(me);
                Signature signature = signatureFactory.signatureInstance();
                PublicKey receivedPublicKey = signatureFactory.decodePublicKey(me);
                signature.initVerify(receivedPublicKey);
                message.signatureForVerification(signature, receivedPublicKey);
                lastContent = contentTypes.poll();
                break;
            default:
            case USER1:
            case USER2:
            case USER3:
            case EMPTY:
                break;
            }
        }
        if (message.isSign()) {
            if (buf.readableBytes() < Number160.BYTE_ARRAY_SIZE + Number160.BYTE_ARRAY_SIZE) {
                return false;
            }
            byte[] me = new byte[Number160.BYTE_ARRAY_SIZE];
            buf.readBytes(me);
            Number160 number1 = new Number160(me);
            buf.readBytes(me);
            Number160 number2 = new Number160(me);
            SHA1Signature signatureEncode = new SHA1Signature(number1, number2);
            message.receivedSignature(signatureEncode);
        }
        return true;
    }

    public Message prepareFinish() {
        Message ret = message;
        message.setDone();
        contentTypes.clear();
        //
        message = null;
        neighborSize = -1;
        neighborSet = null;
        keyCollectionSize = -1;
        keyCollection = null;
        mapsSize = -1;
        dataMap = null;
        data = null;
        keyMap480Size = -1;
        keyMap480 = null;
        bufferSize = -1;
        buffer = null;
        return ret;
    }

    public Message message() {
        return message;
    }

    public Content lastContent() {
        return lastContent;
    }
}