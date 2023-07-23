package com.abin.mallchat.custom.user.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.abin.mallchat.common.chat.dao.RoomFriendDao;
import com.abin.mallchat.common.chat.domain.entity.RoomFriend;
import com.abin.mallchat.common.chat.service.ContactService;
import com.abin.mallchat.common.chat.service.RoomService;
import com.abin.mallchat.common.common.annotation.RedissonLock;
import com.abin.mallchat.common.common.domain.vo.request.PageBaseReq;
import com.abin.mallchat.common.common.domain.vo.response.PageBaseResp;
import com.abin.mallchat.common.common.event.UserApplyEvent;
import com.abin.mallchat.common.common.utils.AssertUtil;
import com.abin.mallchat.common.user.dao.UserApplyDao;
import com.abin.mallchat.common.user.dao.UserFriendDao;
import com.abin.mallchat.common.user.domain.entity.UserApply;
import com.abin.mallchat.common.user.domain.entity.UserFriend;
import com.abin.mallchat.custom.chat.service.ChatService;
import com.abin.mallchat.custom.chat.service.adapter.MessageAdapter;
import com.abin.mallchat.custom.user.domain.vo.request.friend.FriendApplyReq;
import com.abin.mallchat.custom.user.domain.vo.request.friend.FriendApproveReq;
import com.abin.mallchat.custom.user.domain.vo.request.friend.FriendCheckReq;
import com.abin.mallchat.custom.user.domain.vo.response.friend.FriendApplyResp;
import com.abin.mallchat.custom.user.domain.vo.response.friend.FriendCheckResp;
import com.abin.mallchat.custom.user.domain.vo.response.friend.FriendUnreadResp;
import com.abin.mallchat.custom.user.service.FriendService;
import com.abin.mallchat.custom.user.service.WebSocketService;
import com.abin.mallchat.custom.user.service.adapter.FriendAdapter;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.abin.mallchat.common.user.domain.enums.ApplyStatusEnum.AGREE;

/**
 * @author : limeng
 * @description : 好友
 * @date : 2023/07/19
 */
@Slf4j
@Service
public class FriendServiceImpl implements FriendService {

    @Autowired
    private WebSocketService webSocketService;

    @Autowired
    private UserFriendDao userFriendDao;
    @Autowired
    private UserApplyDao userApplyDao;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Autowired
    private RoomFriendDao roomFriendDao;
    @Autowired
    private RoomService roomService;
    @Autowired
    private ContactService contactService;
    @Autowired
    private ChatService chatService;

    /**
     * 检查
     * 检查是否是自己好友
     *
     * @param uid     uid
     * @param request 请求
     * @return {@link FriendCheckResp}
     */
    @Override
    public FriendCheckResp check(Long uid, FriendCheckReq request) {
        List<UserFriend> friendList = userFriendDao.getByFriends(uid, request.getUidList());

        Set<Long> friendUidSet = friendList.stream().map(UserFriend::getFriendUid).collect(Collectors.toSet());
        List<FriendCheckResp.FriendCheck> friendCheckList = request.getUidList().stream().map(friendUid -> {
            FriendCheckResp.FriendCheck friendCheck = new FriendCheckResp.FriendCheck();
            friendCheck.setUid(friendUid);
            friendCheck.setIsFriend(friendUidSet.contains(friendUid));
            return friendCheck;
        }).collect(Collectors.toList());
        return new FriendCheckResp(friendCheckList);
    }

    /**
     * 申请好友
     *
     * @param request 请求
     */
    @Override
    public void apply(Long uid, FriendApplyReq request) {
        //是否有好友关系
        UserFriend friend = userFriendDao.getByFriend(uid, request.getTargetUid());
        AssertUtil.isEmpty(friend, "你们已经是好友了");
        //是否有待审批的申请记录
        UserApply friendApproving = userApplyDao.getFriendApproving(uid, request.getTargetUid());
        if (Objects.nonNull(friendApproving)) {
            log.info("已有好友申请记录,uid:{}, targetId:{}", uid, request.getTargetUid());
            return;
        }
        //申请入库
        UserApply insert = FriendAdapter.buildFriendApply(uid, request);
        userApplyDao.save(insert);
        //申请事件
        applicationEventPublisher.publishEvent(new UserApplyEvent(this, insert));
    }

    /**
     * 分页查询好友申请
     *
     * @param request 请求
     * @return {@link PageBaseResp}<{@link FriendApplyResp}>
     */
    @Override
    public PageBaseResp<FriendApplyResp> pageApplyFriend(Long uid, PageBaseReq request) {
        IPage<UserApply> userApplyIPage = userApplyDao.FriendApplyPage(uid, request.plusPage());
        if (CollectionUtil.isEmpty(userApplyIPage.getRecords())) {
            return PageBaseResp.empty();
        }
        //将这些申请列表设为已读
        List<Long> applyIds = userApplyIPage.getRecords().stream().map(UserApply::getId).collect(Collectors.toList());
        userApplyDao.readApples(uid, applyIds);
        //返回消息
        return PageBaseResp.init(userApplyIPage, FriendAdapter.buildFriendApplyList(userApplyIPage.getRecords()));
    }

    /**
     * 申请未读数
     *
     * @return {@link FriendUnreadResp}
     */
    @Override
    public FriendUnreadResp unread(Long uid) {
        Integer unReadCount = userApplyDao.getUnReadCount(uid);
        return new FriendUnreadResp(unReadCount);
    }

    @Override
    @Transactional
    @RedissonLock(key = "#uid")
    public void applyApprove(Long uid, FriendApproveReq request) {
        UserApply userApply = userApplyDao.getById(request.getApplyId());
        AssertUtil.isNotEmpty(userApply, "不存在申请记录");
        AssertUtil.equal(userApply.getTargetId(), uid, "不存在申请记录");
        AssertUtil.equal(userApply.getStatus(), AGREE.getCode(), "已同意好友申请");
        //同意申请
        userApplyDao.agree(request.getApplyId());
        //创建双方好友关系
        createFriend(uid, userApply.getUid());
        //创建一个聊天房间
        RoomFriend roomFriend = roomService.createFriendRoom(Arrays.asList(uid, userApply.getUid()));
        //创建双方的会话
        contactService.createContact(uid, roomFriend.getRoomId());
        contactService.createContact(userApply.getUid(), roomFriend.getRoomId());
        //发送一条同意消息。。我们已经是好友了，开始聊天吧
        chatService.sendMsg(MessageAdapter.buildAgreeMsg(roomFriend.getRoomId()), uid);
    }

    private void createFriend(Long uid, Long targetUid) {
        UserFriend userFriend1 = new UserFriend();
        userFriend1.setUid(uid);
        userFriend1.setFriendUid(targetUid);
        UserFriend userFriend2 = new UserFriend();
        userFriend2.setUid(targetUid);
        userFriend2.setFriendUid(uid);
        userFriendDao.saveBatch(Lists.newArrayList(userFriend1, userFriend2))
    }
}