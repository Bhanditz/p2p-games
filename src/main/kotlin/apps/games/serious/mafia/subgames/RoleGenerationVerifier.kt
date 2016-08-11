package apps.games.serious.mafia.subgames

import apps.games.primitives.Deck
import entity.User
import org.apache.commons.codec.digest.DigestUtils
import java.math.BigInteger

/**
 * Created by user on 8/11/16.
 */

class RoleGenerationVerifier(originalRoleDeck: RoleDeck){
    val roleDeck: RoleDeck
    init {
        roleDeck = originalRoleDeck.clone()
    }
    fun verify(roleKeys: Map<User, Collection<BigInteger>>, VKeys: Map<User, Collection<BigInteger>>,
               Rs : Map<User, Collection<BigInteger>>,
               Xs: Map<User, Collection<BigInteger>>): Boolean{
        if(!registerRoleKeys(roleKeys)){
            return false
        }
        if(!registerVKeys(VKeys)){
            return false
        }
        if(!checkR(Rs)){
            return false
        }
        if(!checkX(Xs)){
            return false
        }
        return true
    }

    /**
     * Check that keys, provided by user are consistent with recorded
     * hashes and original role deck
     *
     * @param roleKeys - maps user to list of his keys for roles
     */
    private fun registerRoleKeys(roleKeys: Map<User, Collection<BigInteger>>): Boolean{
        for((user, keyset) in roleKeys){
            val h = DigestUtils.sha256Hex(keyset.joinToString(" "))
            if(roleDeck.roleKeyHashes[user] != h){
                return false
            }
            roleDeck.shuffledRoles.decryptSeparate(keyset)
        }
        val sameRoles = roleDeck.shuffledRoles.cards.all { x -> roleDeck.originalRoles.cards.contains(x) }
        if(!sameRoles){
            return false
        }
        return true
    }

    /**
     * Check that keys, provided by user are consistent with recorded
     * hashes
     *
     * @param VKeys - maps user to list of his keys for IV
     */
    private fun registerVKeys(VKeys: Map<User, Collection<BigInteger>>): Boolean{
        for((user, keyset) in VKeys){
            val h = DigestUtils.sha256Hex(keyset.joinToString(" "))
            if(roleDeck.VkeyHashes[user] != h){
                return false
            }
            roleDeck.V.decryptSeparate(keyset)
        }
        return true
    }

    /**
     * Check, that provided Rs indeed sum up into commonR
     *
     * @param Rs - maps user to his original R
     */
    private fun checkR(Rs : Map<User, Collection<BigInteger>>): Boolean{
        val testR = Array(roleDeck.ownR.size, {i -> BigInteger.ONE})
        for((k, v) in Rs){
            if(v.size != testR.size){
                return false
            }
            for(i in 0..v.size-1){
                testR[i] *= v.elementAt(i)
                testR[i] %= roleDeck.V.ECParams.n
            }
        }
        return testR.toList() == roleDeck.commonR
    }

    /**
     * Check that privided Xs sum up into user X
     */
    private fun checkX(Xs: Map<User, Collection<BigInteger>>): Boolean{
        val testDeck = roleDeck.V.clone()
        for(i in 0..roleDeck.shuffledRoles.size-1){
            val role = roleDeck.shuffledRoles.cards[i]
            val pos = roleDeck.originalRoles.cards.indexOf(role)
            testDeck.cards[pos] = roleDeck.V.cards[i]
        }
        for((k, v) in Xs){
            if(v.size != testDeck.size){
                return false
            }
            testDeck.decryptSeparate(v)
        }
        testDeck.decryptSeparate(roleDeck.commonR)
        return testDeck.cards.all { x -> x == testDeck.ECParams.g }
    }

}