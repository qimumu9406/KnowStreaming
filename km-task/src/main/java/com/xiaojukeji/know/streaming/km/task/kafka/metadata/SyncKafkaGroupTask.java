package com.xiaojukeji.know.streaming.km.task.kafka.metadata;

import com.didiglobal.logi.job.annotation.Task;
import com.didiglobal.logi.job.common.TaskResult;
import com.didiglobal.logi.job.core.consensual.ConsensualEnum;
import com.didiglobal.logi.log.ILog;
import com.didiglobal.logi.log.LogFactory;
import com.xiaojukeji.know.streaming.km.common.bean.entity.cluster.ClusterPhy;
import com.xiaojukeji.know.streaming.km.common.bean.entity.group.Group;
import com.xiaojukeji.know.streaming.km.common.utils.ValidateUtils;
import com.xiaojukeji.know.streaming.km.core.service.group.GroupService;
import com.xiaojukeji.know.streaming.km.core.service.topic.TopicService;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;


@Task(name = "SyncKafkaGroupTask",
        description = "KafkaGroup信息同步到DB",
        cron = "0 0/1 * * * ? *",
        autoRegister = true,
        consensual = ConsensualEnum.BROADCAST,
        timeout = 2 * 60)
public class SyncKafkaGroupTask extends AbstractAsyncMetadataDispatchTask {
    private static final ILog log = LogFactory.getLog(SyncKafkaGroupTask.class);

    @Autowired
    private GroupService groupService;

    @Autowired
    private TopicService topicService;

    @Override
    public TaskResult processClusterTask(ClusterPhy clusterPhy, long triggerTimeUnitMs) throws Exception {
        // 获取集群的Group列表
        List<String> groupNameList = groupService.listGroupsFromKafka(clusterPhy.getId());

        TaskResult allSuccess = TaskResult.SUCCESS;

        // 获取Group详细信息
        List<Group> groupList = new ArrayList<>();
        for (String groupName : groupNameList) {
            try {
                Group group = groupService.getGroupFromKafka(clusterPhy.getId(), groupName);
                if (group == null) {
                    continue;
                }

                groupList.add(group);
            } catch (Exception e) {
                log.error("method=processClusterTask||clusterPhyId={}||groupName={}||errMsg=exception", clusterPhy.getId(), groupName, e);
                allSuccess = TaskResult.FAIL;
            }
        }

        // 过滤掉无效的Topic
        this.filterTopicIfTopicNotExist(clusterPhy.getId(), groupList);

        // 更新DB中的Group信息
        groupService.batchReplaceGroupsAndMembers(clusterPhy.getId(), groupList, triggerTimeUnitMs);

        // 如果存在错误，则直接返回
        if (!TaskResult.SUCCESS.equals(allSuccess)) {
            return allSuccess;
        }

        // 删除历史的Group
        groupService.deleteByUpdateTimeBeforeInDB(clusterPhy.getId(), new Date(triggerTimeUnitMs - 5 * 60 * 1000));

        return allSuccess;
    }

    private void filterTopicIfTopicNotExist(Long clusterPhyId, List<Group> groupList) {
        if (ValidateUtils.isEmptyList(groupList)) {
            return;
        }

        // 集群Topic集合
        Set<String> dbTopicSet = topicService.listTopicsFromDB(clusterPhyId).stream().map(elem -> elem.getTopicName()).collect(Collectors.toSet());
        dbTopicSet.add("");   //兼容没有消费Topic的group

        // 过滤Topic不存在的消费组
        for (Group group: groupList) {
            group.setTopicMembers(
                    group.getTopicMembers().stream().filter(elem -> dbTopicSet.contains(elem.getTopicName())).collect(Collectors.toList())
            );
        }
    }
}
