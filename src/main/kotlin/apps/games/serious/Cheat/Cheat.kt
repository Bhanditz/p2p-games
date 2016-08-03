package apps.games.serious.Cheat

import apps.chat.Chat
import apps.games.Game
import apps.games.GameExecutionException
import apps.games.serious.Card
import apps.games.serious.Cheat.GUI.CheatGame
import apps.games.serious.CardGame
import apps.games.serious.Cheat.logger.CheatGameLogger
import apps.games.serious.Pip
import apps.games.serious.getCardById
import apps.games.serious.preferans.Bet
import apps.games.serious.preferans.GUI.PreferansGame
import apps.games.serious.preferans.ShuffledDeck
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import crypto.RSA.RSAKeyManager
import crypto.random.randomString
import entity.Group
import entity.User
import org.apache.commons.codec.digest.DigestUtils
import org.bouncycastle.crypto.InvalidCipherTextException
import proto.GameMessageProto
import sun.misc.Queue
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue

/**
 * Created by user on 7/28/16.
 */

class Cheat(chat: Chat, group: Group, gameID: String) :
                                            CardGame(chat, group, gameID, 36) {
    override val name: String
        get() = "Cheat Game "

    private enum class State{
        INIT,
        VALIDATE_KEYS,
        PICK_DECK_SIZE,
        GENERATE_DECK,
        DECRYPT_HAND,
        INIT_ROUND,
        VERIFY_CARD,
        END,
        ENDLESS_LOOP
    }

    private var state: State = State.INIT

    private lateinit var gameGUI: CheatGame
    private lateinit var application: LwjglApplication

    private val keyManager = RSAKeyManager()
    private val HANDSHAKE_PHRASE = "HANDSHAKE" // who would've thought
    private val N = group.users.size
    private val SALT_SIZE = 128
    private val cardHolders: MutableMap<Int, Int> = mutableMapOf()
    private val playerCards = mutableSetOf<Card>()
    private val playQueue = mutableListOf<List<String>>()
    private val extraData = mutableListOf<ByteArray>()

    val logger = CheatGameLogger(N)

    override fun evaluate(responses: List<GameMessageProto.GameStateMessage>): String {
        for(msg in responses){
            chat.showMessage("[${msg.user.name}] said ${msg.value}")
        }
        extraData.clear()
        when(state){

            State.INIT -> {
                for(msg in responses){
                    keyManager.registerUser(User(msg.user), msg.value)
                }
                initGame()
                currentPlayerID = -1
                state = State.VALIDATE_KEYS
            }
            State.VALIDATE_KEYS -> {
                if(playerID == currentPlayerID){
                    for(msg in responses){
                        try {
                            val s = keyManager.decodeString(msg.value)
                            val handshake = s.split(" ").last()
                            if(handshake != HANDSHAKE_PHRASE){
                                throw GameExecutionException("Invalid RSA key")
                            }
                        }catch (e: InvalidCipherTextException){
                            throw GameExecutionException("Malformed RSA key")
                        }
                    }
                }

                currentPlayerID ++
                if(currentPlayerID == N){
                    state = State.PICK_DECK_SIZE
                    chat.sendMessage("RSA is OK. Generating deck")
                    currentPlayerID = -1
                    return ""

                }
                val s = randomString(512) + " " + HANDSHAKE_PHRASE
                return keyManager.encodeForUser(playerOrder[currentPlayerID], s)
            }
            State.PICK_DECK_SIZE -> {
                val vote = pickDeckSize()
                state = State.GENERATE_DECK
                return vote.toString()
            }
            State.GENERATE_DECK -> {
                for (msg in responses){
                    chat.showMessage("[${msg.user.name}] voted for ${msg.value} cardID deck")
                }
                val votes = responses.map { x -> x.value.toInt() }
                DECK_SIZE = votes.maxBy { x -> votes.count { s -> s == x } } ?:
                        throw GameExecutionException("Couldn't agree in deck size")
                gameGUI.updateDeckSize(DECK_SIZE)
                chat.sendMessage("We are playing $DECK_SIZE cardID deck")
                state = State.DECRYPT_HAND
                return initiateHands()
            }
            State.DECRYPT_HAND -> {
                decryptHand(responses)
                dealHand()
                state = State.INIT_ROUND
                currentPlayerID = -1
            }
            State.INIT_ROUND -> {
                val action = processGameResponses(responses)
                if(action == Choice.ADD){
                    return makeMove()
                }else{
                    state = State.VERIFY_CARD
                }

            }
            State.VERIFY_CARD -> {
                TODO()
            }
            State.ENDLESS_LOOP -> {

            }
            State.END -> TODO()
        }
        return ""
    }

    /**
     * Start GUI for the Preferans game
     */
    private fun initGame(): String {
        val config = LwjglApplicationConfiguration()
        config.width = 1024
        config.height = 1024
        config.forceExit = false
        config.title = "Cheate Game[${chat.username}]"
        gameGUI = CheatGame(playerID, N = N)
        application = LwjglApplication(gameGUI, config)
        while (!gameGUI.loaded) {
            Thread.sleep(200)
        }
        return ""
    }


    /**
     * Let users pick desired DeckSize
     */
    private fun pickDeckSize(): Int{
        val deckSizeQueue = LinkedBlockingQueue<DeckSizes>(1)
        val callback = {x: DeckSizes -> deckSizeQueue.offer(x)}
        gameGUI.resetAllSizes()
        gameGUI.registerDeckSizeCallback(callback, *DeckSizes.values())
        gameGUI.showDecksizeOverlay()
        gameGUI.showHint("Pick desired deck size")
        val x = deckSizeQueue.take()
        gameGUI.hideDecksizeOverlay()
        return x.size
    }

    /**
     * Create new deck. Broadcast key for
     * cardID holders
     */
    private fun initiateHands(): String {
        gameGUI.showHint("Shuffling Cards")
        updateDeck()
        logger.newRound(DECK_SIZE, deck)
        val resultKeys = mutableListOf<BigInteger>()
        for (i in 0..DECK_SIZE-1) {
            val holder = i % N
            cardHolders[i] = holder
            if (holder != playerID) {
                resultKeys.add(deck.encrypted.keys[i])
            }
        }
        return resultKeys.joinToString(" ")
    }

    /**
     * Take a list of keys sent by user,
     * keys should correspond to cards, that are not
     * by that user. I.E. If player holds cards
     * 1, 4, 7 in shuffled deck, keys - contains
     * key for every cardID, that is not in TALON,
     * and are not possesed by that user
     * Stores known cards in
     */
    private fun decryptHand(responses: List<GameMessageProto.GameStateMessage>){
        for(i in 0..DECK_SIZE-1){
            logger.log.registerCardKey(playerID, i, deck.encrypted.keys[i])
        }
        for (msg in responses){
            val keys = msg.value.split(" ").map { x -> BigInteger(x) }
            val userID = getUserID(User(msg.user))
            val positions = cardHolders.filterValues { x -> x != userID }.keys.toList()
            if(keys.size != positions.size){
                throw GameExecutionException("Someone failed to provide his keys")
            }
            for(i in 0..keys.size-1){
                val pos = logger.log.registerCardKey(userID, positions[i], keys[i])
                if(pos != -1 && cardHolders[positions[i]] == playerID){
                    playerCards.add(getCardById(pos, DECK_SIZE))
                }
            }
        }
    }

    /**
     * Deal decrypted cards to myself and unknown cards
     * to everyone else
     */
    private fun dealHand(){
        for(card in playerCards){
            gameGUI.dealPlayer(getTablePlayerId(playerID), card)
        }
        for(i in 0..DECK_SIZE-1){
            if(cardHolders[i] != playerID){
                gameGUI.dealPlayer(getTablePlayerId(cardHolders[i]!!), -1)
            }
        }
    }

    /**
     * Process messages receive from last turn
     * update logger, choose next player to make his move
     *
     * @return player action(Choice)
     */
    private fun processGameResponses(responses: List<GameMessageProto.GameStateMessage>): Choice {
        for(msg in responses){
            val user = User(msg.user)
            val userID = getUserID(user)
            if(userID == currentPlayerID){
                val move = msg.value.split(" ")
                val action: Choice = Choice.valueOf(move[0])
                when(action){
                    Choice.ADD -> {
                        processAddCardHashes(msg)
                        currentPlayerID = (currentPlayerID + 1) % N
                    }
                    Choice.CHECK_TRUE -> TODO()
                    Choice.CHECK_FALSE -> TODO()
                }
                return action
            }
        }
        return Choice.ADD
    }

    /**
     * previous player decided to add cards
     * @param msg - GameStateMessage describing addedcards
     */
    private fun processAddCardHashes(msg: GameMessageProto.GameStateMessage){
        val move = msg.value.split(" ")
        val user = User(msg.user)
        val userID = getUserID(user)
        if(move.size < 2 || move.size > 4){
            throw GameExecutionException("Unexpectex move: ${msg.value}")
        }
        val claim = if(move.size == 2) Pip.UNKNOWN else Pip.valueOf(move[2])
        if(claim != Pip.UNKNOWN){
            gameGUI.showHint("All of these cards are ${claim.type}")
        }
        val count = BetCount.valueOf(move[1])
        val hashes = msg.dataList.map { x -> String(x.toByteArray()) }
        logger.log.registerAddPlayHashes(user, count, claim, hashes)
        if(playerID != currentPlayerID){
            for(i in 0..count.size-1){
                gameGUI.animateCardPlay(getTablePlayerId(userID))
            }
        }

    }


    /**
     * If we are currentPlayer - make our move
     * Pick our claim(add card/belive/check for cheat)
     * if necessary - pick cards and return their hashes
     * otherwise - update hint
     */
    private fun makeMove(): String{
        if(currentPlayerID == -1){
            currentPlayerID ++
        }
        if(playerID == currentPlayerID){
            gameGUI.showHint("Decide what will you do:")
            val choice = getPlayerChoise()
            if(choice == Choice.ADD){
                val count = getAddCount()
                val pip = getPip()
                val pickedCards = Array(count.size, {i -> gameGUI.pickCard(*playerCards.toTypedArray())})

                val stored = pickedCards
                        .map { x -> deck.encrypted.deck.cards.indexOf(deck.originalDeck.cards[x]) }
                        .map { x -> "$x ${deck.encrypted.keys[x]} ${randomString(SALT_SIZE)}" }
                playQueue.add(stored)
                extraData.clear()
                for(cardEntry in stored){
                    extraData.add(DigestUtils.sha256Hex(cardEntry.toByteArray()).toByteArray())
                }
                return "${choice.name} ${count.type} ${pip.type}"
            }else{
                var prevPlayer = (currentPlayerID - 1)
                if(prevPlayer < 0){
                    prevPlayer += N
                }
                val cardToVerify = gameGUI.pickPlayedCard(getTablePlayerId(prevPlayer))
                return "${choice.name} $cardToVerify"
            }
        }else{
            gameGUI.showHint("Waiting for " +
                    "[${playerOrder[currentPlayerID].name}]" +
                    "to make his move")
        }
        return ""
    }

    /**
     * Get player Choice from UI
     */
    private fun getPlayerChoise(): Choice{
        if(logger.log.isNewStack()){
            return Choice.ADD
        }
        val choiceQueue = LinkedBlockingQueue<Choice>(1)
        val callback = {x: Choice -> choiceQueue.offer(x)}
        gameGUI.registerChoicesCallback(callback, *Choice.values())
        gameGUI.resetAllChoices()
        gameGUI.showChoicesOverlay()
        val x = choiceQueue.take()
        gameGUI.hideChoicesOverlay()
        return x
    }


    /**
     * Get number of cards that player will play this round
     */
    private fun getAddCount(): BetCount{
        val betCountQueue = LinkedBlockingQueue<BetCount>(1)
        val callback = {x: BetCount -> betCountQueue.offer(x)}
        gameGUI.registerBetCountsCallback(callback, *BetCount.values())
        gameGUI.resetAllBetCounts()
        gameGUI.showBetCountOverlay()
        val x = betCountQueue.take()
        gameGUI.hideBetCountOverlay()
        return x
    }

    /**
     * Get pip of cards that will be claimed this round
     */
    private fun getPip(): Pip {
        if(!logger.log.isNewStack()){
            return Pip.UNKNOWN
        }
        val pipQueue = LinkedBlockingQueue<Pip>(1)
        val callback = {x: Pip -> pipQueue.offer(x)}
        gameGUI.registerPipCallback(callback, *Pip.values())
        gameGUI.resetAllPips()
        gameGUI.showPipOverlay()
        val x = pipQueue.take()
        gameGUI.hidePipOverlay()
        return x
    }

    override fun isFinished(): Boolean {
        return state == State.END
    }

    override fun getInitialMessage(): String {
        return keyManager.getPublicKey()
    }

    override fun getResult() {}

    override fun getData(): List<ByteArray> {
        return extraData
    }
}
