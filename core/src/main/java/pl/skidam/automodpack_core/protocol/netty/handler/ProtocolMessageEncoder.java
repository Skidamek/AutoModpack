package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import pl.skidam.automodpack_core.protocol.netty.message.*;

import static pl.skidam.automodpack_core.protocol.NetUtils.*;

public class ProtocolMessageEncoder extends MessageToByteEncoder<ProtocolMessage> {
    @Override
    protected void encode(ChannelHandlerContext ctx, ProtocolMessage msg, ByteBuf out) throws Exception {
        out.writeByte(msg.getVersion());
        out.writeByte(msg.getType());
        out.writeBytes(msg.getSecret());

        switch (msg.getType()) {
            case ECHO_TYPE:
                EchoMessage echoMsg = (EchoMessage) msg;
                out.writeInt(echoMsg.getDataLength());
                out.writeBytes(echoMsg.getData());
                break;
            case FILE_REQUEST_TYPE:
                FileRequestMessage fileRequestMessage = (FileRequestMessage) msg;
                out.writeInt(fileRequestMessage.getFileHashLength());
                out.writeBytes(fileRequestMessage.getFileHash());
                break;
            case FILE_RESPONSE_TYPE:
                FileResponseMessage fileResponseMessage = (FileResponseMessage) msg;
                out.writeInt(fileResponseMessage.getDataLength());
                out.writeBytes(fileResponseMessage.getData());
                break;
            case REFRESH_REQUEST_TYPE:
                RefreshRequestMessage refreshRequestMessage = (RefreshRequestMessage) msg;
                out.writeInt(refreshRequestMessage.getFileHashesCount());
                out.writeInt(refreshRequestMessage.getFileHashesLength());
                for (byte[] fileHash : refreshRequestMessage.getFileHashesList()) {
                    out.writeBytes(fileHash);
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown message type: " + msg.getType());
        }
    }
}
