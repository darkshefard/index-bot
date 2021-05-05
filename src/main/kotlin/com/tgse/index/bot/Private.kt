package com.tgse.index.bot

import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.*
import com.tgse.index.bot.execute.BlacklistExecute
import com.tgse.index.bot.execute.EnrollExecute
import com.tgse.index.bot.execute.RecordExecute
import com.tgse.index.datasource.*
import com.tgse.index.msgFactory.ListMsgFactory
import com.tgse.index.msgFactory.MineMsgFactory
import com.tgse.index.msgFactory.NormalMsgFactory
import com.tgse.index.msgFactory.RecordMsgFactory
import com.tgse.index.nick
import com.tgse.index.provider.BotProvider
import com.tgse.index.provider.WatershedProvider
import com.tgse.index.provider.WatershedProvider.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*

/**
 * 私聊
 */
@Service
class Private(
    private val blacklistExecute: BlacklistExecute,
    private val enrollExecute: EnrollExecute,
    private val recordExecute: RecordExecute,
    private val botProvider: BotProvider,
    private val watershedProvider: WatershedProvider,
    private val normalMsgFactory: NormalMsgFactory,
    private val recordMsgFactory: RecordMsgFactory,
    private val mineMsgFactory: MineMsgFactory,
    private val listMsgFactory: ListMsgFactory,
    private val enrollElastic: EnrollElastic,
    private val recordElastic: RecordElastic,
    private val userElastic: UserElastic,
    private val blacklist: Blacklist,
    private val telegram: Telegram,
    private val awaitStatus: AwaitStatus
) {

    private val logger = LoggerFactory.getLogger(Private::class.java)

    init {
        subscribeUpdate()
        subscribeApprove()
    }

    private fun subscribeUpdate() {
        watershedProvider.requestObservable.subscribe(
            { request ->
                try {
                    if (request !is BotPrivateRequest) return@subscribe
                    // 输入状态
                    botProvider.sendTyping(request.chatId)
                    // 回执
                    when {
                        request.update.callbackQuery() != null -> executeByButton(request)
                        awaitStatus.getAwaitStatus(request.chatId) != null -> {
                            val callbackData = awaitStatus.getAwaitStatus(request.chatId)!!.callbackData
                            if (callbackData.startsWith("approve") || callbackData.startsWith("enroll"))
                                enrollExecute.executeByStatus(EnrollExecute.Type.Enroll, request)
                            else if (callbackData.startsWith("update"))
                                recordExecute.executeByStatus(request)
                            else
                                executeByStatus(EnrollExecute.Type.Enroll, request)
                        }
                        request.update.message().text().startsWith("/") -> executeByCommand(request)
                        request.update.message().text().startsWith("@") -> executeByEnroll(request)
                        request.update.message().text().startsWith("https://t.me/") -> executeByEnroll(request)
                        else -> executeByText(request)
                    }
                } catch (e: Throwable) {
                    botProvider.sendErrorMessage(e)
                    e.printStackTrace()
                }finally {
                    // 记录日活用户
                    val user = when {
                        request.update.message() != null -> request.update.message().from()
                        else -> request.update.callbackQuery().message().from()
                    }
                    userElastic.footprint(user)
                }
            },
            { throwable ->
                throwable.printStackTrace()
                logger.error("Private.error")
                botProvider.sendErrorMessage(throwable)
            },
            {
                logger.error("Private.complete")
            }
        )
    }

    private fun subscribeApprove() {
        enrollElastic.submitApproveObservable.subscribe(
            { (enroll, manager, isPassed) ->
                try {
                    val msg = recordMsgFactory.makeApproveResultMsg(enroll.createUser, enroll, isPassed)
                    botProvider.send(msg)
                } catch (e: Throwable) {
                    botProvider.sendErrorMessage(e)
                    e.printStackTrace()
                }
            },
            { throwable ->
                throwable.printStackTrace()
                logger.error("Private.approve.error")
                botProvider.sendErrorMessage(throwable)
            },
            {
                logger.error("Private.approve.complete")
            }
        )
    }

    private fun executeByText(request: BotPrivateRequest) {
        val keywords = request.update.message().text()
        val msg = listMsgFactory.makeListFirstPageMsg(request.chatId, keywords, 1) ?: normalMsgFactory.makeReplyMsg(    request.chatId, "nothing"   )
        botProvider.send(msg)
    }

    private fun executeByEnroll(request: BotPrivateRequest) {
        // 人员黑名单检测
        val userBlack = blacklist.get(request.chatId)
        if (userBlack != null) {
            val user = request.update.message().from()
            val telegramPerson = Telegram.TelegramPerson(request.chatId, user.username(), user.nick(), null)
            blacklistExecute.notify(request.chatId, telegramPerson)
            return
        }
        // 获取收录内容
        val username = request.update.message().text().replaceFirst("@", "").replaceFirst("https://t.me/", "")
        val telegramMod = telegram.getTelegramModFromWeb(username)
        // 收录对象黑名单检测
        val recordBlack = blacklist.get(username)
        if (recordBlack != null && telegramMod != null) {
            blacklistExecute.notify(request.chatId, telegramMod)
            return
        }
        // 检测是否已有提交或已收录
        val enrollExist = enrollElastic.getSubmittedEnrollByUsername(username)
        val record = recordElastic.getRecordByUsername(username)
        if (enrollExist != null || record != null) {
            val msg = normalMsgFactory.makeReplyMsg(request.chatId, "exist")
            botProvider.send(msg)
            return
        }
        // 回执
        val sendMessage = when (telegramMod) {
            is Telegram.TelegramGroup -> {
                normalMsgFactory.makeReplyMsg(request.chatId, "enroll-need-join-group")
            }
            is Telegram.TelegramChannel -> {
                val enroll = EnrollElastic.Enroll(
                    UUID.randomUUID().toString(),
                    Telegram.TelegramModType.Channel,
                    null,
                    telegramMod.title,
                    telegramMod.description,
                    null,
                    null,
                    telegramMod.username,
                    null,
                    telegramMod.members,
                    Date().time,
                    request.chatId,
                    request.update.message().from().nick(),
                    false,
                    null
                )
                val createEnroll = enrollElastic.addEnroll(enroll)
                if (!createEnroll) return
                recordMsgFactory.makeEnrollMsg(request.chatId, enroll)
            }
            is Telegram.TelegramBot -> {
                val enroll = EnrollElastic.Enroll(
                    UUID.randomUUID().toString(),
                    Telegram.TelegramModType.Bot,
                    null,
                    telegramMod.title,
                    telegramMod.description,
                    null,
                    null,
                    telegramMod.username,
                    null,
                    null,
                    Date().time,
                    request.chatId,
                    request.update.message().from().nick(),
                    false,
                    null
                )
                val createEnroll = enrollElastic.addEnroll(enroll)
                if (!createEnroll) return
                recordMsgFactory.makeEnrollMsg(request.chatId, enroll)
            }
            else -> normalMsgFactory.makeReplyMsg(request.chatId, "nothing")
        }
        sendMessage.disableWebPagePreview(true)
        sendMessage.parseMode(ParseMode.HTML)
        botProvider.send(sendMessage)
    }

    private fun executeByCommand(request: BotPrivateRequest) {
        // 获取命令内容
        val cmd = request.update.message().text().replaceFirst("/", "").replace("@${botProvider.username}", "")
        // 回执
        val sendMessage = when {
            cmd == "start" -> normalMsgFactory.makeReplyMsg(request.chatId, "start")
            cmd.startsWith("start ") -> executeBySuperCommand(request)
            cmd == "enroll" -> normalMsgFactory.makeReplyMsg(request.chatId, cmd)
            cmd == "update" || cmd == "mine" -> mineMsgFactory.makeListFirstPageMsg(request.update.message().from())
            cmd == "list" -> normalMsgFactory.makeListReplyMsg(request.chatId)
            cmd == "setting" -> normalMsgFactory.makeReplyMsg(request.chatId, "only-group")
            cmd == "help" -> normalMsgFactory.makeReplyMsg(request.chatId, "help-private")
            else -> normalMsgFactory.makeReplyMsg(request.chatId, "can-not-understand")
        }
        sendMessage.disableWebPagePreview(true)
        sendMessage.parseMode(ParseMode.HTML)
        botProvider.send(sendMessage)
    }

    private fun executeBySuperCommand(request: BotPrivateRequest): SendMessage {
        val recordUUID = request.update.message().text().replaceFirst("/start ", "")
        val record = recordElastic.getRecord(recordUUID)!!
        return recordMsgFactory.makeRecordMsg(request.chatId, record)
    }

    private fun executeByButton(request: BotPrivateRequest) {
        val answer = AnswerCallbackQuery(request.update.callbackQuery().id())
        botProvider.send(answer)

        val callbackData = request.update.callbackQuery().data()
        when {
            callbackData.startsWith("enroll") || callbackData.startsWith("enroll-class") -> {
                enrollExecute.executeByEnrollButton(EnrollExecute.Type.Enroll, request)
            }
            callbackData.startsWith("update") || callbackData.startsWith("record-class") -> {
                recordExecute.executeByRecordButton(request)
            }
            callbackData.startsWith("page") -> {
                val callback = callbackData.replace("page:", "").split("&")
                val keywords = callback[0]
                val pageIndex = callback[1].toInt()
                val msg = listMsgFactory.makeListNextPageMsg(request.chatId,request.messageId!!, keywords, pageIndex)
                botProvider.send(msg)
            }
            callbackData.startsWith("mine") -> {
                val pageIndex = callbackData.replace("mine:", "").toInt()
                val user = request.update.callbackQuery().from()
                val msg = mineMsgFactory.makeListNextPageMsg(user, request.messageId!!, pageIndex)
                botProvider.send(msg)
            }
            callbackData.startsWith("feedback") -> {
                awaitStatus.setAwaitStatus(request.chatId, AwaitStatus.Await(request.messageId!!, callbackData))
                val msg = normalMsgFactory.makeReplyMsg(request.chatId, "feedback-start")
                botProvider.send(msg)
            }
        }
    }

    fun executeByStatus(type: EnrollExecute.Type, request: BotRequest) {
        val statusCallbackData = awaitStatus.getAwaitStatus(request.chatId!!)!!.callbackData
        when {
            statusCallbackData.startsWith("feedback:") -> {
                val recordUUID = statusCallbackData.replace("feedback:", "")
                val content = request.update.message().text()
                val feedback = Pair(recordUUID, content)
                watershedProvider.feedbackSubject.onNext(feedback)
                // 清除状态
                awaitStatus.clearAwaitStatus(request.chatId!!)
                val msg = normalMsgFactory.makeReplyMsg(request.chatId!!, "feedback-finish")
                botProvider.send(msg)
            }
        }
    }
}