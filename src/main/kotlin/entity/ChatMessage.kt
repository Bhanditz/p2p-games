package entity

import proto.ChatMessageProto

/**
 * Created by user on 6/24/16.
 */

class ChatMessage(val chatId: Int, val user: User, val message: String): ProtobufSerializable<ChatMessageProto.ChatMessage>{
    constructor(msg: ChatMessageProto.ChatMessage): this(msg.chatId, User(msg.user), msg.message){}

    override fun getProto(): ChatMessageProto.ChatMessage {
        return ChatMessageProto.ChatMessage.newBuilder()
                                    .setChatId(chatId)
                                    .setMessage(message)
                                    .setUser(user.getProto()).build()
    }

}