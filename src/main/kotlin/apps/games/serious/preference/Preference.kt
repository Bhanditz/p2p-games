package apps.games.serious.preference

import apps.chat.Chat
import apps.games.Game
import apps.games.GameExecutionException
import apps.games.primitives.Deck
import apps.games.primitives.EncryptedDeck
import apps.games.primitives.protocols.DeckShuffleGame
import apps.games.primitives.protocols.RandomDeckGame
import apps.games.serious.preference.GUI.PreferenceGame
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import entity.ChatMessage
import entity.Group
import entity.User
import org.bouncycastle.jce.ECNamedCurveTable
import proto.GameMessageProto
import java.math.BigInteger
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * Created by user on 7/6/16.
 */

class Preference(chat: Chat, group: Group, gameID: String) : Game<Unit>(chat, group, gameID){
    override val name: String
        get() = "Preference Card Game"

    private enum class State{
        INIT,
        ROUND_INIT,
        DECRYPT_HAND,
        END
    }

    private val ECParams = ECNamedCurveTable.getParameterSpec("secp256k1")
    private var state: State = State.INIT

    private lateinit var gameGUI: PreferenceGame

    private val DECK_SIZE = 32
    //TALON - always last two cards of the deck
    private val TALON = 2

    //to sorted array to preserve order
    private val playerOrder: List<User> = group.users.sortedBy { x -> x.name }
    private val playerID = playerOrder.indexOf(chat.me())

    //Required - three players.
    //TODO - add checker for number of players

    private val N = 3
    private val cardHolders: MutableMap<Int, Int> = mutableMapOf()

    //shuffled Deck
    private lateinit var deck: ShuffledDeck

    override fun getInitialMessage(): String {
        return playerOrder.hashCode().toString()
    }

    override fun isFinished(): Boolean {
        return state == State.END
    }

    override fun evaluate(responses: List<GameMessageProto.GameStateMessage>): String {
        when(state){
            State.INIT -> {
                //validate player order
                val hashes = responses.distinctBy {x -> x.value}
                if(hashes.size != 1){
                    throw GameExecutionException("Someone has different deck")
                }

                val config = LwjglApplicationConfiguration()
                config.width = 1024
                config.height = 1024
                config.forceExit = false
                gameGUI = PreferenceGame()
                LwjglApplication(gameGUI, config)
                while(!gameGUI.loaded){
                    Thread.sleep(200)
                }
                state = State.ROUND_INIT
            }
            State.ROUND_INIT -> {
                //If we can not create deck - game aborted
                deck = newDeck() ?: return ""
                //Deal all cards, except last two
                cardHolders.clear()
                val resultKeys = mutableListOf<BigInteger>()

                for(i in 0..deck.originalDeck.size-1-TALON){
                    val holder = i % N
                    cardHolders[i] = holder
                    if(holder != playerID){
                        resultKeys.add(deck.encrypted.keys[i])
                    }
                }
                state = State.DECRYPT_HAND
                return resultKeys.joinToString(" ")
            }
            State.DECRYPT_HAND -> {
                deck.encrypted.deck.decryptSeparate(deck.encrypted.keys)
                for(msg in responses){
                    // do not process messages from self
                    if(User(msg.user) == chat.me()){
                        continue
                    }
                    val keys = msg.value.split(" ").map { x -> BigInteger(x) }
                    decryptWithUserKeys(User(msg.user), keys)
                }
                dealHands()
                state = State.END
            }
            State.END -> {}
        }
        return ""
    }

    fun getUserID(user: User): Int{
        return playerOrder.indexOf(user)
    }

    override fun getResult() {
        return Unit
    }

    /**
     * Create a new deck and shuffle it.
     * In preference this is executed before
     * each round
     * @return Pair of original Deck and
     * shuffle result - EncryptedDeck
     */
    private fun newDeck(): ShuffledDeck?{
        val deckFuture = runSubGame(RandomDeckGame(chat, group.clone(), subGameID(), ECParams, DECK_SIZE))
        val deck: Deck
        try{
            deck = deckFuture.get()
        }catch(e: CancellationException){ // Task was cancelled - means that we need to stop. NOW!
            state = State.END
            return null
        }catch(e: ExecutionException){
            chat.showMessage(ChatMessage(chat, e.message?: "Something went wrong"))
            e.printStackTrace()
            throw GameExecutionException("Subgame failed")
        }

        val shuffleFuture = runSubGame(DeckShuffleGame(chat, group.clone(), subGameID(), ECParams, deck.clone()))
        val shuffled: EncryptedDeck
        try{
            shuffled = shuffleFuture.get()
        }catch(e: CancellationException){ // Task was cancelled - means that we need to stop. NOW!
            state = State.END
            return null
        }catch(e: ExecutionException){
            chat.showMessage(ChatMessage(chat, e.message?: "Something went wrong"))
            e.printStackTrace()
            throw GameExecutionException("Subgame failed")
        }
        return ShuffledDeck(deck, shuffled)
    }

    /**
     * Take a list of keys sent by user,
     * keys should correspond to cards, that are not
     * by that user. I.E. If player holds cards
     * 1, 4, 7 in shuffled deck, keys - contains
     * key for every card, that is not in TALON,
     * and are not possesed by that user
     */
    private fun decryptWithUserKeys(user: User, keys: List<BigInteger>){
        val positions = mutableListOf<Int>()
        for(key in cardHolders.keys){
            if(cardHolders[key] != getUserID(user)){
                positions.add(key)
            }
        }
        if(positions.size != keys.size){
            throw GameExecutionException("Someone failed to provide correct keys for encrypted cards")
        }
        for(i in 0..positions.size-1){
            val position = positions[i]
            deck.encrypted.deck.decryptCardWithKey(position, keys[i])
        }
    }

    /**
     * Give each player cards, that
     * belong to his hand(GUI)
     */
    private fun dealHands(){
        gameGUI.tableScreen.showDeck()
        for(i in 0..DECK_SIZE-TALON-1){
            val cardID: Int
            if(cardHolders[i] == playerID){
                cardID = deck.originalDeck.cards.indexOf(deck.encrypted.deck.cards[i])
            }else{
                cardID = -1
            }
            var currentPlayerId: Int = cardHolders[i]?: throw GameExecutionException("Invalid card distribution")
            currentPlayerId -= playerID
            if(currentPlayerId < 0){
                currentPlayerId += N
            }
            gameGUI.dealPlayer(currentPlayerId, cardID)
        }
        //Deal unknown Talon cards
        for(i in 1..TALON){
            gameGUI.dealCommon(-1)
        }
        gameGUI.tableScreen.hideDeck()
    }

}

data class ShuffledDeck(val originalDeck: Deck,val encrypted: EncryptedDeck)