package com.github.paicoding.forum.service.chatai.service;

import com.github.paicoding.forum.api.model.enums.ai.AiChatStatEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatItemVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.core.cache.RedisClient;
import com.github.paicoding.forum.service.chatai.constants.ChatConstants;
import com.github.paicoding.forum.service.user.service.UserAiHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 聊天的抽象模板类
 *
 * @author YiHui
 * @date 2023/6/9
 */
@Slf4j
@Service
public abstract class AbsChatService implements ChatService {
    @Autowired
    private UserAiHistoryService userAiHistoryService;
    /**
     * 查询已经使用的次数
     *
     * @param user
     * @return
     */
    protected int queryUserdCnt(Long user) {
        Integer cnt = RedisClient.hGet(ChatConstants.getAiRateKey(source()), String.valueOf(user), Integer.class);
        if (cnt == null) {
            cnt = 0;
        }
        return cnt;
    }


    /**
     * 使用次数+1
     *
     * @param user
     * @return
     */
    protected Long incrCnt(Long user) {
        return RedisClient.hIncr(ChatConstants.getAiRateKey(source()), String.valueOf(user), 1);
    }

    /**
     * 保存聊天记录
     */
    protected void recordChatItem(Long user, ChatItemVo item) {
        // 写入 MySQL
        userAiHistoryService.pushChatItem(source(), user, item);

        // 写入 Redis
        RedisClient.lPush(ChatConstants.getAiHistoryRecordsKey(source(), user), item);
    }

    /**
     * 查询用户的聊天历史
     *
     * @return
     */
    public ChatRecordsVo getChatHistory(Long user) {
        List<ChatItemVo> chats = RedisClient.lRange(ChatConstants.getAiHistoryRecordsKey(source(), user), 0, 50, ChatItemVo.class);
        chats.add(0, new ChatItemVo().initAnswer("开始你和派聪明的AI之旅吧!"));
        ChatRecordsVo vo = new ChatRecordsVo();
        vo.setMaxCnt(getMaxQaCnt(user));
        vo.setUsedCnt(queryUserdCnt(user));
        vo.setSource(source());
        vo.setRecords(chats);
        return vo;
    }

    @Override
    public ChatRecordsVo chat(Long user, String question) {
        ChatRecordsVo res = initResVo(user, question);
        if (!res.hasQaCnt()) {
            return res;
        }

        answer(user, res);
        return res;
    }

    @Override
    public ChatRecordsVo chat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
        ChatRecordsVo res = initResVo(user, question);
        if (!res.hasQaCnt()) {
            return res;
        }

        // 同步聊天时，直接返回结果
        answer(user, res);
        consumer.accept(res);
        return res;
    }

    private ChatRecordsVo initResVo(Long user, String question) {
        ChatRecordsVo res = new ChatRecordsVo();
        res.setSource(source());
        int maxCnt = getMaxQaCnt(user);
        int usedCnt = queryUserdCnt(user);
        res.setMaxCnt(maxCnt);
        res.setUsedCnt(usedCnt);

        ChatItemVo item = new ChatItemVo().initQuestion(question);
        if (!res.hasQaCnt()) {
            // 次数已经使用完毕
            item.initAnswer(ChatConstants.TOKEN_OVER);
        }
        res.setRecords(Arrays.asList(item));
        return res;
    }

    protected AiChatStatEnum answer(Long user, ChatRecordsVo res) {
        ChatItemVo itemVo = res.getRecords().get(0);
        AiChatStatEnum ans = doAnswer(user, itemVo);
        if (ans == AiChatStatEnum.END) {
            processAfterSuccessedAnswered(user, res);
        }
        return ans;
    }

    /**
     * 提问，并将结果写入chat
     *
     * @param user
     * @param chat
     * @return true 表示正确回答了； false 表示回答出现异常
     */
    public abstract AiChatStatEnum doAnswer(Long user, ChatItemVo chat);

    /**
     * 成功返回之后的后置操作
     *
     * @param user
     * @param response
     */
    protected void processAfterSuccessedAnswered(Long user, ChatRecordsVo response) {
        // 回答成功，保存聊天记录，剩余次数-1
        incrCnt(user);
        recordChatItem(user, response.getRecords().get(0));
        response.setUsedCnt(response.getUsedCnt() + 1);
        if (response.getUsedCnt() > ChatConstants.MAX_HISTORY_RECORD_ITEMS) {
            // 最多保存五百条历史聊天记录
            RedisClient.lTrim(ChatConstants.getAiHistoryRecordsKey(source(), user), 0, ChatConstants.MAX_HISTORY_RECORD_ITEMS);
        }
    }

    /**
     * 异步聊天，即提问并不要求直接得到接口；等后台准备完毕之后再写入对应的结果
     *
     * @param user
     * @param question
     * @param consumer 执行成功之后，直接异步回调的通知
     * @return
     */
    @Override
    public ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
        ChatRecordsVo res = initResVo(user, question);
        if (!res.hasQaCnt()) {
            return res;
        }

        final ChatRecordsVo newRes = res.clone();
        AiChatStatEnum needReturn = doAsyncAnswer(user, newRes, (ans, vo) -> {
            // ai异步返回结果之后，我们将结果推送给前端用户
            consumer.accept(newRes);
            if (ans == AiChatStatEnum.END) {
                // 只有最后一个会话，即ai的回答结束，才需要进行持久化，并计数
                processAfterSuccessedAnswered(user, newRes);
            }
        });

        if (needReturn.needResponse()) {
            // 异步响应时，直接返回一个稍等得提示文案
            ChatItemVo nowItem = res.getRecords().get(0);
            nowItem.initAnswer(ChatConstants.ASYNC_CHAT_TIP);
            consumer.accept(res);
        }
        return res;
    }

    /**
     * 异步返回结果
     *
     * @param user
     * @param response 保存提问 & 返回的结果，最终会返回给前端用户
     * @param consumer 具体将 response 写回前端的实现策略
     * @return 返回的会话状态，控制是否需要将结果直接返回给前端
     */
    public abstract AiChatStatEnum doAsyncAnswer(Long user, ChatRecordsVo response, BiConsumer<AiChatStatEnum, ChatRecordsVo> consumer);

    /**
     * 查询当前用户最多可提问的次数
     *
     * @param user
     * @return
     */
    protected int getMaxQaCnt(Long user) {
        return ChatConstants.MAX_CHATGPT_QAS_CNT;
    }
}
