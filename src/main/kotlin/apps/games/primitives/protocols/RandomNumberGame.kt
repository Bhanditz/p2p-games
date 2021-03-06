package apps.games.primitives.protocols

import apps.chat.Chat
import apps.games.Game
import apps.games.GameManager
import apps.games.GameManagerClass
import crypto.random.randomBigInt
import crypto.random.randomString
import entity.ChatMessage
import entity.Group
import entity.User
import org.apache.commons.codec.digest.DigestUtils
import proto.GameMessageProto
import java.math.BigInteger

/**
 * Created by user on 6/27/16.
 *
 *
 * Class describes protocol for generating common
 * random number.
 *
 * Algorithm has four stages:
 *
 * INIT - protocol just started, each playerId generates his own
 * random number and broadcasts it
 *
 * GENERATE - each playerId processes random numbers received from
 * other players and computes their sum - that is common random
 * number
 *
 * VALIDATE_ROLES - all players verify, that everyone have the same
 * result
 *
 * END - end the protocol
 */

class RandomNumberGame(chat: Chat, group: Group,
                       gameID: String, minValue: BigInteger = BigInteger.valueOf(
        Int.MIN_VALUE.toLong()),
                       maxValue: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong()),
                       gameManager: GameManagerClass = GameManager) : Game<BigInteger>(chat,
        group, gameID, gameManager) {
    override val name: String
        get() = "Random Number Generator"

    constructor(chat: Chat, group: Group,
                gameID: String,
                minValue: Long,
                maxValue: Long,
                gameManager: GameManagerClass = GameManager) : this(chat, group,
            gameID, BigInteger.valueOf(minValue),
            BigInteger.valueOf(maxValue), gameManager) {
    }

    constructor(chat: Chat, group: Group, gameID: String, bits: Int, gameManager: GameManagerClass = GameManager) :
    this(chat, group, gameID, BigInteger.ZERO, BigInteger.valueOf(2).pow(bits),
            gameManager) {
    }


    private val offset = minValue
    private val n: BigInteger = maxValue - minValue + BigInteger.ONE

    private enum class State {
        INIT,
        GENERATE,
        VALIDATE,
        END
    }

    private var state: State = State.INIT
    private val myRandom: BigInteger = randomBigInt(n)
    private var answer: BigInteger = BigInteger.ZERO
    private val salt: String = randomString(100)
    private val hashes: MutableSet<String> = mutableSetOf()
    private var agreed: Boolean = true

    override fun evaluate(responses: List<GameMessageProto.GameStateMessage>): String {
        when (state) {
            State.INIT -> {
                state = State.GENERATE
                return DigestUtils.sha256Hex(myRandom.toString() + salt)
            }
            State.GENERATE -> {
                state = State.VALIDATE

                for (msg in responses) {
                    chat.showMessage(ChatMessage(chat.chatId, User(msg.user),
                            "Hash: ${msg.value}"))
                    hashes.add(msg.value)
                }
                return myRandom.toString() + " " + salt
            }
            State.VALIDATE -> {
                for (msg in responses) {
                    val res = checkAnswer(msg.value)
                    if (res == null) {
                        agreed = false
                        break
                    }
                    chat.showMessage(ChatMessage(chat.chatId, User(msg.user),
                            "User data: ${msg.value}"))
                    answer += res
                    answer %= n
                    if (answer < BigInteger.ZERO) {
                        answer += n
                    }
                    answer += offset
                }
                state = State.END
                return ""
            }
            State.END -> {
                return ""
            }
        }

    }

    override fun isFinished(): Boolean {
        return state == State.END
    }

    override fun getFinalMessage(): String {
        if (agreed) {
            return "Everything is OK!\n Agreed on $answer"
        } else {
            return "Someone cheated"
        }
    }

    override fun getResult(): BigInteger {
        return answer
    }

    private fun checkAnswer(s: String): BigInteger? {
        val split = s.split(" ")
        if (split.size != 2) {
            return null
        }
        val toSHA256: String = split.joinToString("")
        if (!hashes.contains(DigestUtils.sha256Hex(toSHA256))) {
            return null
        }
        return BigInteger(split[0])
    }

}