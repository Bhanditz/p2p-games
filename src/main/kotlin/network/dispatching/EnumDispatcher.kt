package network.dispatching

import com.google.protobuf.Descriptors
import com.google.protobuf.GeneratedMessage
import com.sun.javaws.exceptions.InvalidArgumentException
import proto.GenericMessageProto

/**
 * Created by user on 6/21/16.
 */
//TODO - better way to dispatch
/**
 * Dispatch messages for given aggregator type
 *
 * Message structure:
 *
 * enum Type{
 *  type1 = 1
 *  type2 = 2
 *  type3 = 3
 *  .......
 *  typeN = k
 * }
 *
 * Type type = 1
 *
 * optional Type1 type1 = 2
 * optional Type2 type2 = 3
 * ....
 * optional TypeN = k+1
 *
 *
 * No simple way to check function parameter types(for now)
 */


class EnumDispatcher<T : GeneratedMessage> : Dispatcher<T>{
    // Todo: mb somehow infere type(Get rid of Any).
    // Problem: we dont want to specify both Enum type and GeneratedMessage, as one
    // is inferior to the other

    /**
     * storage for lsiteners
     */
    //Todo - maybe we need to return PromiseFuture/execute in pool. Proof of concept for now
    private val listeners: Map<Descriptors.EnumValueDescriptor, MutableList<Dispatcher<*>>>

    /**
     * store info about given class: protobuf type, java type and descryptor for getting value
     */
    private val enumType: Descriptors.EnumDescriptor
    private val enumClass: Class<*>
    private val enumFieldDescriptor: Descriptors.FieldDescriptor

    /**
     * @param sample: Take sample ofbject(i.e. default instacne) and initialize info about class
     */
    constructor(sample: T) {

        //TODO - custom exceptions
        enumType = sample.descriptorForType.findEnumTypeByName("Type") ?:
                throw InvalidArgumentException(Array(1, { x: Int -> "Provided type has no Type Enum subclass" }))

        enumClass = sample.javaClass.declaredClasses.firstOrNull { x -> x.isEnum && x.simpleName.equals("Type") } ?:
                throw InvalidArgumentException(Array(1, { x: Int -> "Provided type has no Type Enum subclass" }))

        enumFieldDescriptor = sample.descriptorForType.findFieldByName("type")

        val entries: MutableList<Pair<Descriptors.EnumValueDescriptor, MutableList<Dispatcher<*>>>> = mutableListOf()
        for (value in enumType.values) {
            entries.add(Pair(value, mutableListOf()))
        }

        listeners = mapOf(*entries.toTypedArray())
    }

    /**
     * add Dispatcher as listener. Useful, when protobuf
     * has nested enum structure
     * @param x - value of Enum type
     * @param listener - actor, that takes place, when event x
     * occured.
     */
    fun <E : Enum<E>, T : GeneratedMessage> register(x: E, listener: Dispatcher<T>) {
        if (!enumClass.isInstance(x)) {
            throw InvalidArgumentException(Array(1, { x: Int -> "type of provided event doesn't correspond to this Dispatcher handle type" }))
        }
        listeners[enumType.findValueByName(x.name)]!!.add(listener)
    }

    /**
     * add endpoint listener
     * @param x - value of Enum type
     * @param listener - actor, that takes place, when event x
     * occured.
     */
    fun <E : Enum<E>, T : GeneratedMessage> register(x: E, listener: (T) -> (GenericMessageProto.GenericMessage?)) {
        register(x, SimpleDispatcher(listener))
    }

    /**
     * Parse message. Call all listeners, that are
     * listening to corresponding Enum type
     */
    override fun dispatch(message: T): GenericMessageProto.GenericMessage? {
        val type = message.getField(enumFieldDescriptor)
        // By convention typenumber + 1 = field number
        val fieldNumber = (type as Descriptors.EnumValueDescriptor).number + 1
        val nesterMessage = message.getField(message.descriptorForType.findFieldByNumber(fieldNumber))
        for (listener in getHandlers(type, (nesterMessage as GeneratedMessage).javaClass)) {
            listener.dispatch(nesterMessage)
        }
        //TDO combine listeners output to single message
        return null
    }


    @Suppress("UNCHECKED_CAST")
    private fun <T : GeneratedMessage> getHandlers(eventType: Descriptors.EnumValueDescriptor, eventValueType: Class<T>)
            : List<Dispatcher<T>> {
        val list = listeners[eventType]
        return list as List<Dispatcher<T>>
    }

}