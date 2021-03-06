package apps.games

import apps.chat.Chat
import apps.games.serious.Cheat.Cheat
import apps.games.serious.lotto.Lotto
import apps.games.serious.mafia.Mafia
import apps.games.serious.preferans.Preferans
import entity.Group

/**
 * Created by user on 6/27/16.
 */

class GameFactory {
    companion object {
        private val games = listOf("Lotto", "Preferans", "Cheat", "Mafia")
        fun getGameNames() = games
        fun instantiateGame(name: String,
                            chat: Chat,
                            group: Group,
                            gameID: String): Game<Unit> {
            when (name) {
                "Lotto" -> return Lotto(chat, group, gameID)
                "Preferans" -> return Preferans(chat, group, gameID)
                "Cheat" -> return Cheat(chat, group, gameID)
                "Mafia" -> return Mafia(chat, group, gameID)
            }
            throw IllegalArgumentException("No such game")
        }
    }
}