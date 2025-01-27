package io.metersphere.track.service;


import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.metersphere.api.dto.automation.ApiScenarioDTO;
import io.metersphere.api.dto.automation.ScenarioStatus;
import io.metersphere.api.dto.automation.TestPlanScenarioRequest;
import io.metersphere.api.dto.definition.ApiTestCaseRequest;
import io.metersphere.api.dto.definition.TestPlanApiCaseDTO;
import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.*;
import io.metersphere.base.mapper.ext.ExtTestCaseMapper;
import io.metersphere.base.mapper.ext.ExtTestPlanMapper;
import io.metersphere.base.mapper.ext.ExtTestPlanTestCaseMapper;
import io.metersphere.commons.constants.NoticeConstants;
import io.metersphere.commons.constants.TestPlanStatus;
import io.metersphere.commons.constants.TestPlanTestCaseStatus;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.user.SessionUser;
import io.metersphere.commons.utils.*;
import io.metersphere.dto.BaseSystemConfigDTO;
import io.metersphere.i18n.Translator;
import io.metersphere.notice.sender.NoticeModel;
import io.metersphere.notice.service.NoticeSendService;
import io.metersphere.service.SystemParameterService;
import io.metersphere.track.Factory.ReportComponentFactory;
import io.metersphere.track.domain.ReportComponent;
import io.metersphere.track.dto.TestCaseReportMetricDTO;
import io.metersphere.track.dto.TestPlanCaseDTO;
import io.metersphere.track.dto.TestPlanDTO;
import io.metersphere.track.dto.TestPlanDTOWithMetric;
import io.metersphere.track.request.testcase.PlanCaseRelevanceRequest;
import io.metersphere.track.request.testcase.QueryTestPlanRequest;
import io.metersphere.track.request.testplan.AddTestPlanRequest;
import io.metersphere.track.request.testplancase.QueryTestPlanCaseRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class TestPlanService {
    @Resource
    TestPlanMapper testPlanMapper;
    @Resource
    ExtTestPlanMapper extTestPlanMapper;
    @Resource
    ExtTestPlanTestCaseMapper extTestPlanTestCaseMapper;
    @Resource
    TestCaseMapper testCaseMapper;
    @Resource
    TestPlanTestCaseMapper testPlanTestCaseMapper;
    @Resource
    SqlSessionFactory sqlSessionFactory;
    @Lazy
    @Resource
    TestPlanTestCaseService testPlanTestCaseService;
    @Resource
    TestCaseReportMapper testCaseReportMapper;
    @Resource
    TestPlanProjectMapper testPlanProjectMapper;
    @Resource
    TestPlanProjectService testPlanProjectService;
    @Resource
    ProjectMapper projectMapper;
    @Resource
    ExtTestCaseMapper extTestCaseMapper;
    @Resource
    UserMapper userMapper;
    @Resource
    private NoticeSendService noticeSendService;
    @Resource
    private SystemParameterService systemParameterService;
    @Resource
    private TestPlanApiCaseService testPlanApiCaseService;
    @Resource
    private TestPlanScenarioCaseService testPlanScenarioCaseService;
    @Resource
    private TestPlanLoadCaseService testPlanLoadCaseService;

    public synchronized void addTestPlan(AddTestPlanRequest testPlan) {
        if (getTestPlanByName(testPlan.getName()).size() > 0) {
            MSException.throwException(Translator.get("plan_name_already_exists"));
        }

        String testPlanId = UUID.randomUUID().toString();

        List<String> projectIds = testPlan.getProjectIds();
        projectIds.forEach(id -> {
            TestPlanProject testPlanProject = new TestPlanProject();
            testPlanProject.setProjectId(id);
            testPlanProject.setTestPlanId(testPlanId);
            testPlanProjectMapper.insertSelective(testPlanProject);
        });

        testPlan.setId(testPlanId);
        testPlan.setStatus(TestPlanStatus.Prepare.name());
        testPlan.setCreateTime(System.currentTimeMillis());
        testPlan.setUpdateTime(System.currentTimeMillis());
        testPlan.setCreator(SessionUtils.getUser().getId());
        testPlan.setProjectId(SessionUtils.getCurrentProjectId());
        testPlanMapper.insert(testPlan);

        List<String> userIds = new ArrayList<>();
        userIds.add(testPlan.getPrincipal());
        String context = getTestPlanContext(testPlan, NoticeConstants.Event.CREATE);
        User user = userMapper.selectByPrimaryKey(testPlan.getCreator());
        Map<String, Object> paramMap = getTestPlanParamMap(testPlan);
        paramMap.put("creator", user.getName());
        NoticeModel noticeModel = NoticeModel.builder()
                .context(context)
                .relatedUsers(userIds)
                .subject(Translator.get("test_plan_notification"))
                .mailTemplate("TestPlanStart")
                .paramMap(paramMap)
                .event(NoticeConstants.Event.CREATE)
                .build();
        noticeSendService.send(NoticeConstants.TaskType.TEST_PLAN_TASK, noticeModel);
    }

    public List<TestPlan> getTestPlanByName(String name) {
        TestPlanExample example = new TestPlanExample();
        example.createCriteria().andWorkspaceIdEqualTo(SessionUtils.getCurrentWorkspaceId())
                .andNameEqualTo(name);
        return testPlanMapper.selectByExample(example);
    }

    public TestPlan getTestPlan(String testPlanId) {
        return Optional.ofNullable(testPlanMapper.selectByPrimaryKey(testPlanId)).orElse(new TestPlan());
    }

    public int editTestPlan(TestPlanDTO testPlan) {
        editTestPlanProject(testPlan);
        testPlan.setUpdateTime(System.currentTimeMillis());
        checkTestPlanExist(testPlan);
        //进行中状态，写入实际开始时间
        if (TestPlanStatus.Underway.name().equals(testPlan.getStatus())) {
            testPlan.setActualStartTime(System.currentTimeMillis());
        } else if (TestPlanStatus.Completed.name().equals(testPlan.getStatus())) {
            //已完成，写入实际完成时间
            testPlan.setActualEndTime(System.currentTimeMillis());

        }
        List<String> userIds = new ArrayList<>();
        userIds.add(testPlan.getPrincipal());
        AddTestPlanRequest testPlans = new AddTestPlanRequest();
        int i = testPlanMapper.updateByPrimaryKeySelective(testPlan);
        if (!StringUtils.isBlank(testPlan.getStatus())) {
            BeanUtils.copyBean(testPlans, getTestPlan(testPlan.getId()));
            String context = getTestPlanContext(testPlans, NoticeConstants.Event.UPDATE);
            User user = userMapper.selectByPrimaryKey(testPlans.getCreator());
            Map<String, Object> paramMap = getTestPlanParamMap(testPlans);
            paramMap.put("creator", user.getName());
            NoticeModel noticeModel = NoticeModel.builder()
                    .context(context)
                    .relatedUsers(userIds)
                    .subject(Translator.get("test_plan_notification"))
                    .mailTemplate("TestPlanEnd")
                    .paramMap(paramMap)
                    .event(NoticeConstants.Event.UPDATE)
                    .build();
            noticeSendService.send(NoticeConstants.TaskType.TEST_PLAN_TASK, noticeModel);
        }
        return i;
    }

    private void editTestPlanProject(TestPlanDTO testPlan) {
        // 将要进行关联的项目ID
        List<String> projectIds = testPlan.getProjectIds();
        // 如果将要关联的项目ID中包含测试计划所属ID则进行剔除
        if (!CollectionUtils.isEmpty(projectIds)) {
            if (projectIds.contains(testPlan.getProjectId())) {
                projectIds.remove(testPlan.getProjectId());
            }
        }
        // todo 优化； TestPlanList intoPlan 方法会触发此更新
        if (StringUtils.isNotBlank(testPlan.getProjectId())) {
            TestPlanProjectExample testPlanProjectExample1 = new TestPlanProjectExample();
            testPlanProjectExample1.createCriteria().andTestPlanIdEqualTo(testPlan.getId());
            List<TestPlanProject> testPlanProjects = testPlanProjectMapper.selectByExample(testPlanProjectExample1);
            // 已经关联的项目idList
            List<String> dbProjectIds = testPlanProjects.stream().map(TestPlanProject::getProjectId).collect(Collectors.toList());
            // 修改后传过来的项目idList，如果还未关联，进行关联
            projectIds.forEach(projectId -> {
                if (!dbProjectIds.contains(projectId)) {
                    TestPlanProject testPlanProject = new TestPlanProject();
                    testPlanProject.setTestPlanId(testPlan.getId());
                    testPlanProject.setProjectId(projectId);
                    testPlanProjectMapper.insert(testPlanProject);
                }
            });

            TestPlanProjectExample testPlanProjectExample = new TestPlanProjectExample();
            TestPlanProjectExample.Criteria criteria1 = testPlanProjectExample.createCriteria();
            criteria1.andTestPlanIdEqualTo(testPlan.getId());
            if (!CollectionUtils.isEmpty(projectIds)) {
                criteria1.andProjectIdNotIn(projectIds);
            }
            testPlanProjectMapper.deleteByExample(testPlanProjectExample);

            // 关联的项目下的用例idList
            List<String> caseIds = null;
            // 测试计划所属项目下的用例不解除关联
            projectIds.add(testPlan.getProjectId());
            if (!CollectionUtils.isEmpty(projectIds)) {
                TestCaseExample example = new TestCaseExample();
                example.createCriteria().andProjectIdIn(projectIds);
                List<TestCase> caseList = testCaseMapper.selectByExample(example);
                caseIds = caseList.stream().map(TestCase::getId).collect(Collectors.toList());
            }

            // 取消关联项目下的用例和计划的关系
            TestPlanTestCaseExample testPlanTestCaseExample = new TestPlanTestCaseExample();
            TestPlanTestCaseExample.Criteria criteria = testPlanTestCaseExample.createCriteria().andPlanIdEqualTo(testPlan.getId());
            if (!CollectionUtils.isEmpty(caseIds)) {
                criteria.andCaseIdNotIn(caseIds);
            }
            testPlanTestCaseMapper.deleteByExample(testPlanTestCaseExample);

            List<String> relevanceProjectIds = new ArrayList<>();
            relevanceProjectIds.add(testPlan.getProjectId());
            if (!CollectionUtils.isEmpty(testPlan.getProjectIds())) {
                relevanceProjectIds.addAll(testPlan.getProjectIds());
            }
            testPlanApiCaseService.deleteByRelevanceProjectIds(testPlan.getId(), relevanceProjectIds);
            testPlanScenarioCaseService.deleteByRelevanceProjectIds(testPlan.getId(), relevanceProjectIds);
            testPlanLoadCaseService.deleteByRelevanceProjectIds(testPlan.getId(), relevanceProjectIds);
        }
    }

    //计划内容
    private Map<String, Object> getTestPlanParamMap(TestPlan testPlan) {
        Long startTime = testPlan.getPlannedStartTime();
        Long endTime = testPlan.getPlannedEndTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = null;
        String sTime = String.valueOf(startTime);
        String eTime = String.valueOf(endTime);
        if (!sTime.equals("null")) {
            start = sdf.format(new Date(Long.parseLong(sTime)));
        }
        String end = null;
        if (!eTime.equals("null")) {
            end = sdf.format(new Date(Long.parseLong(eTime)));
        }

        Map<String, Object> context = new HashMap<>();
        BaseSystemConfigDTO baseSystemConfigDTO = systemParameterService.getBaseInfo();
        context.put("url", baseSystemConfigDTO.getUrl());
        context.put("testPlanName", testPlan.getName());
        context.put("start", start);
        context.put("end", end);
        context.put("id", testPlan.getId());
        String status = "";
        if (StringUtils.equals(TestPlanStatus.Underway.name(), testPlan.getStatus())) {
            status = "进行中";
        } else if (StringUtils.equals(TestPlanStatus.Prepare.name(), testPlan.getStatus())) {
            status = "未开始";
        } else if (StringUtils.equals(TestPlanStatus.Completed.name(), testPlan.getStatus())) {
            status = "已完成";
        }
        context.put("status", status);
        User user = userMapper.selectByPrimaryKey(testPlan.getCreator());
        context.put("creator", user.getName());
        return context;
    }


    private void checkTestPlanExist(TestPlan testPlan) {
        if (testPlan.getName() != null) {
            TestPlanExample example = new TestPlanExample();
            example.createCriteria()
                    .andNameEqualTo(testPlan.getName())
                    .andWorkspaceIdEqualTo(SessionUtils.getCurrentWorkspaceId())
                    .andIdNotEqualTo(testPlan.getId());
            if (testPlanMapper.selectByExample(example).size() > 0) {
                MSException.throwException(Translator.get("plan_name_already_exists"));
            }
        }
    }

    public int deleteTestPlan(String planId) {
        TestPlan testPlan = getTestPlan(planId);
        deleteTestCaseByPlanId(planId);
        testPlanProjectService.deleteTestPlanProjectByPlanId(planId);
        testPlanApiCaseService.deleteByPlanId(planId);
        testPlanScenarioCaseService.deleteByPlanId(planId);
        int num = testPlanMapper.deleteByPrimaryKey(planId);
        List<String> relatedUsers = new ArrayList<>();
        AddTestPlanRequest testPlans = new AddTestPlanRequest();
        relatedUsers.add(testPlan.getCreator());
        try {
            BeanUtils.copyBean(testPlans, testPlan);
            String context = getTestPlanContext(testPlans, NoticeConstants.Event.DELETE);
            User user = userMapper.selectByPrimaryKey(testPlan.getCreator());
            Map<String, Object> paramMap = getTestPlanParamMap(testPlan);
            paramMap.put("creator", user.getName());
            NoticeModel noticeModel = NoticeModel.builder()
                    .context(context)
                    .relatedUsers(relatedUsers)
                    .subject(Translator.get("test_plan_notification"))
                    .mailTemplate("TestPlanDelete")
                    .paramMap(paramMap)
                    .event(NoticeConstants.Event.DELETE)
                    .build();
            noticeSendService.send(NoticeConstants.TaskType.TEST_PLAN_TASK, noticeModel);
        } catch (Exception e) {
            LogUtil.error(e.getMessage(), e);
        }
        return num;
    }

    public void deleteTestCaseByPlanId(String testPlanId) {
        TestPlanTestCaseExample testPlanTestCaseExample = new TestPlanTestCaseExample();
        testPlanTestCaseExample.createCriteria().andPlanIdEqualTo(testPlanId);
        testPlanTestCaseMapper.deleteByExample(testPlanTestCaseExample);
    }

    private void calcTestPlanRate(List<TestPlanDTOWithMetric> testPlans) {
        testPlans.forEach(testPlan -> {
            testPlan.setTested(0);
            testPlan.setPassed(0);
            testPlan.setTotal(0);

            List<String> functionalExecResults = extTestPlanTestCaseMapper.getExecResultByPlanId(testPlan.getId());
            functionalExecResults.forEach(item -> {
                if (!StringUtils.equals(item, TestPlanTestCaseStatus.Prepare.name())
                        && !StringUtils.equals(item, TestPlanTestCaseStatus.Underway.name())) {
                    testPlan.setTested(testPlan.getTested() + 1);
                    if (StringUtils.equals(item, TestPlanTestCaseStatus.Pass.name())) {
                        testPlan.setPassed(testPlan.getPassed() + 1);
                    }
                }
            });

            List<String> apiExecResults = testPlanApiCaseService.getExecResultByPlanId(testPlan.getId());
            apiExecResults.forEach(item -> {
                if (StringUtils.isNotBlank(item)) {
                    testPlan.setTested(testPlan.getTested() + 1);
                    if (StringUtils.equals(item, "success")) {
                        testPlan.setPassed(testPlan.getPassed() + 1);
                    }
                }
            });

            List<String> scenarioExecResults = testPlanScenarioCaseService.getExecResultByPlanId(testPlan.getId());
            scenarioExecResults.forEach(item -> {
                if (StringUtils.isNotBlank(item)) {
                    testPlan.setTested(testPlan.getTested() + 1);
                    if (StringUtils.equals(item, ScenarioStatus.Success.name())) {
                        testPlan.setPassed(testPlan.getPassed() + 1);
                    }
                }
            });

            testPlan.setTotal(apiExecResults.size() + scenarioExecResults.size() + functionalExecResults.size());

            testPlan.setPassRate(MathUtils.getPercentWithDecimal(testPlan.getTested() == 0 ? 0 : testPlan.getPassed() * 1.0 / testPlan.getTested()));
            testPlan.setTestRate(MathUtils.getPercentWithDecimal(testPlan.getTotal() == 0 ? 0 : testPlan.getTested() * 1.0 / testPlan.getTotal()));
        });
    }

    public List<TestPlanDTOWithMetric> listTestPlan(QueryTestPlanRequest request) {
        request.setOrders(ServiceUtils.getDefaultOrder(request.getOrders()));
        String projectId = SessionUtils.getCurrentProjectId();
        if (StringUtils.isNotBlank(projectId)) {
            request.setProjectId(projectId);
        }
        List<TestPlanDTOWithMetric> testPlans = extTestPlanMapper.list(request);
        calcTestPlanRate(testPlans);
        return testPlans;
    }

    public List<TestPlanDTOWithMetric> listTestPlanByProject(QueryTestPlanRequest request) {
        List<TestPlanDTOWithMetric> testPlans=extTestPlanMapper.list(request);
        return testPlans;
    }

    public void testPlanRelevance(PlanCaseRelevanceRequest request) {

        List<String> testCaseIds = request.getTestCaseIds();

        if (testCaseIds.isEmpty()) {
            return;
        }

        // 如果是关联全部指令则根据条件查询未关联的案例
        if (testCaseIds.get(0).equals("all")) {
            List<TestCase> testCases = extTestCaseMapper.getTestCaseByNotInPlan(request.getRequest());
            if (!testCases.isEmpty()) {
                testCaseIds = testCases.stream().map(testCase -> testCase.getId()).collect(Collectors.toList());
            }
        }
        TestCaseExample testCaseExample = new TestCaseExample();
        testCaseExample.createCriteria().andIdIn(testCaseIds);

        Map<String, TestCaseWithBLOBs> testCaseMap =
                testCaseMapper.selectByExampleWithBLOBs(testCaseExample)
                        .stream()
                        .collect(Collectors.toMap(TestCase::getId, testcase -> testcase));

        SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH);
        TestPlanTestCaseMapper batchMapper = sqlSession.getMapper(TestPlanTestCaseMapper.class);

        if (!testCaseIds.isEmpty()) {
            testCaseIds.forEach(caseId -> {
                TestCaseWithBLOBs testCase = testCaseMap.get(caseId);
                TestPlanTestCaseWithBLOBs testPlanTestCase = new TestPlanTestCaseWithBLOBs();
                testPlanTestCase.setId(UUID.randomUUID().toString());
                testPlanTestCase.setExecutor(SessionUtils.getUser().getId());
                testPlanTestCase.setCaseId(caseId);
                testPlanTestCase.setCreateTime(System.currentTimeMillis());
                testPlanTestCase.setUpdateTime(System.currentTimeMillis());
                testPlanTestCase.setPlanId(request.getPlanId());
                testPlanTestCase.setStatus(TestPlanStatus.Prepare.name());
                testPlanTestCase.setResults(testCase.getSteps());
                batchMapper.insert(testPlanTestCase);
            });
        }

        sqlSession.flushStatements();

        TestPlan testPlan = testPlanMapper.selectByPrimaryKey(request.getPlanId());
        if (StringUtils.equals(testPlan.getStatus(), TestPlanStatus.Prepare.name())
                || StringUtils.equals(testPlan.getStatus(), TestPlanStatus.Completed.name())) {
            testPlan.setStatus(TestPlanStatus.Underway.name());
            testPlanMapper.updateByPrimaryKey(testPlan);
        }
    }

    public List<TestPlan> recentTestPlans(String currentWorkspaceId) {
        if (StringUtils.isBlank(currentWorkspaceId)) {
            return null;
        }
        if (StringUtils.isNotBlank(SessionUtils.getCurrentProjectId())) {
            TestPlanExample testPlanExample = new TestPlanExample();
            TestPlanExample.Criteria criteria = testPlanExample.createCriteria();
            criteria.andProjectIdEqualTo(SessionUtils.getCurrentProjectId());
            List<TestPlan> testPlans = testPlanMapper.selectByExample(testPlanExample);
            if (!CollectionUtils.isEmpty(testPlans)) {
                List<String> testPlanIds = testPlans.stream().map(TestPlan::getId).collect(Collectors.toList());
                TestPlanExample testPlanTestCaseExample = new TestPlanExample();
                testPlanTestCaseExample.createCriteria().andWorkspaceIdEqualTo(currentWorkspaceId)
                        .andIdIn(testPlanIds)
                        .andPrincipalEqualTo(SessionUtils.getUserId());
                testPlanTestCaseExample.setOrderByClause("update_time desc");
                return testPlanMapper.selectByExample(testPlanTestCaseExample);
            }
        }
        return new ArrayList<>();
    }

    public List<TestPlan> listTestAllPlan(String currentWorkspaceId) {
        if (StringUtils.isNotBlank(SessionUtils.getCurrentProjectId())) {
            TestPlanExample testPlanExample = new TestPlanExample();
            TestPlanExample.Criteria criteria = testPlanExample.createCriteria();
            criteria.andProjectIdEqualTo(SessionUtils.getCurrentProjectId());
            List<TestPlan> testPlans = testPlanMapper.selectByExample(testPlanExample);
            if (!CollectionUtils.isEmpty(testPlans)) {
                List<String> testPlanIds = testPlans.stream().map(TestPlan::getId).collect(Collectors.toList());
                TestPlanExample testPlanExample1 = new TestPlanExample();
                TestPlanExample.Criteria testPlanCriteria = testPlanExample1.createCriteria();
                testPlanCriteria.andWorkspaceIdEqualTo(currentWorkspaceId);
                testPlanCriteria.andIdIn(testPlanIds);
                return testPlanMapper.selectByExample(testPlanExample1);
            }
        }

        return new ArrayList<>();
    }

    public List<TestPlanDTOWithMetric> listRelateAllPlan() {
        SessionUser user = SessionUtils.getUser();
        QueryTestPlanRequest request = new QueryTestPlanRequest();
        request.setPrincipal(user.getId());
        request.setWorkspaceId(SessionUtils.getCurrentWorkspaceId());
        request.setProjectId(SessionUtils.getCurrentProjectId());
        request.setPlanIds(extTestPlanTestCaseMapper.findRelateTestPlanId(user.getId(), SessionUtils.getCurrentWorkspaceId(), SessionUtils.getCurrentProjectId()));
        List<TestPlanDTOWithMetric> testPlans = extTestPlanMapper.listRelate(request);
        calcTestPlanRate(testPlans);
        return testPlans;
    }

    public List<TestPlanCaseDTO> listTestCaseByPlanId(String planId) {
        QueryTestPlanCaseRequest request = new QueryTestPlanCaseRequest();
        request.setPlanId(planId);
        return testPlanTestCaseService.list(request);
    }

    private List<TestPlanCaseDTO> listTestCaseByProjectIds(List<String> projectIds) {
        if (CollectionUtils.isEmpty(projectIds)) {
            return new ArrayList<>();
        }
        return extTestPlanTestCaseMapper.listTestCaseByProjectIds(projectIds);
    }

    public TestCaseReportMetricDTO getMetric(String planId) {
        QueryTestPlanRequest queryTestPlanRequest = new QueryTestPlanRequest();
        queryTestPlanRequest.setId(planId);

        TestPlanDTO testPlan = extTestPlanMapper.list(queryTestPlanRequest).get(0);
        String projectName = getProjectNameByPlanId(planId);
        testPlan.setProjectName(projectName);

        TestCaseReport testCaseReport = testCaseReportMapper.selectByPrimaryKey(testPlan.getReportId());
        JSONObject content = JSONObject.parseObject(testCaseReport.getContent());
        JSONArray componentIds = content.getJSONArray("components");

        List<ReportComponent> components = ReportComponentFactory.createComponents(componentIds.toJavaList(String.class), testPlan);
        List<Issues> issues = buildFunctionalCaseReport(planId, components);

        TestCaseReportMetricDTO testCaseReportMetricDTO = new TestCaseReportMetricDTO();
        components.forEach(component -> {
            component.afterBuild(testCaseReportMetricDTO);
        });
        testCaseReportMetricDTO.setIssues(issues);
        return testCaseReportMetricDTO;
    }

    public List<TestPlan> getTestPlanByIds(List<String> planIds) {
        TestPlanExample example = new TestPlanExample();
        example.createCriteria().andIdIn(planIds);
        return testPlanMapper.selectByExample(example);
    }

    public void editTestPlanStatus(String planId) {
        List<String> statusList = extTestPlanTestCaseMapper.getStatusByPlanId(planId);
        TestPlan testPlan = new TestPlan();
        testPlan.setId(planId);
        for (String status : statusList) {
            if (StringUtils.equals(status, TestPlanTestCaseStatus.Prepare.name())
                    || StringUtils.equals(status, TestPlanTestCaseStatus.Underway.name())) {
                testPlan.setStatus(TestPlanStatus.Underway.name());
                testPlanMapper.updateByPrimaryKeySelective(testPlan);
                return;
            }
        }
        testPlan.setStatus(TestPlanStatus.Completed.name());
        testPlanMapper.updateByPrimaryKeySelective(testPlan);
        TestPlan testPlans = getTestPlan(planId);
        List<String> userIds = new ArrayList<>();
        userIds.add(testPlans.getCreator());
        AddTestPlanRequest _testPlans = new AddTestPlanRequest();
        if (StringUtils.equals(TestPlanStatus.Completed.name(), testPlans.getStatus())) {
            try {
                BeanUtils.copyBean(_testPlans, testPlans);
                String context = getTestPlanContext(_testPlans, NoticeConstants.Event.UPDATE);
                User user = userMapper.selectByPrimaryKey(_testPlans.getCreator());
                Map<String, Object> paramMap = getTestPlanParamMap(_testPlans);
                paramMap.put("creator", user.getName());
                NoticeModel noticeModel = NoticeModel.builder()
                        .context(context)
                        .relatedUsers(userIds)
                        .subject(Translator.get("test_plan_notification"))
                        .mailTemplate("TestPlanEnd")
                        .paramMap(paramMap)
                        .event(NoticeConstants.Event.UPDATE)
                        .build();
                noticeSendService.send(NoticeConstants.TaskType.TEST_PLAN_TASK, noticeModel);
            } catch (Exception e) {
                LogUtil.error(e.getMessage(), e);
            }
        }

    }

    public String getProjectNameByPlanId(String testPlanId) {
        List<String> projectIds = testPlanProjectService.getProjectIdsByPlanId(testPlanId);
        ProjectExample projectExample = new ProjectExample();
        projectExample.createCriteria().andIdIn(projectIds);

        List<Project> projects = projectMapper.selectByExample(projectExample);
        StringBuilder stringBuilder = new StringBuilder();
        String projectName = "";

        if (projects.size() > 0) {
            for (Project project : projects) {
                stringBuilder.append(project.getName()).append("、");
            }
            projectName = stringBuilder.substring(0, stringBuilder.length() - 1);
        }

        return projectName;
    }

    private String getTestPlanContext(AddTestPlanRequest testPlan, String type) {
        User user = userMapper.selectByPrimaryKey(testPlan.getCreator());
        Long startTime = testPlan.getPlannedStartTime();
        Long endTime = testPlan.getPlannedEndTime();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = null;
        String sTime = String.valueOf(startTime);
        String eTime = String.valueOf(endTime);
        if (!sTime.equals("null")) {
            start = sdf.format(new Date(Long.parseLong(sTime)));
        } else {
            start = "未设置";
        }
        String end = null;
        if (!eTime.equals("null")) {
            end = sdf.format(new Date(Long.parseLong(eTime)));
        } else {
            end = "未设置";
        }
        String context = "";
        if (StringUtils.equals(NoticeConstants.Event.CREATE, type)) {
            context = "测试计划任务通知：" + user.getName() + "创建的" + "'" + testPlan.getName() + "'" + "待开始，计划开始时间是:" + "'" + start + "'" + ";" + "计划结束时间是:" + "'" + end + "'" + " " + "请跟进";
        } else if (StringUtils.equals(NoticeConstants.Event.UPDATE, type)) {
            String status = "";
            if (StringUtils.equals(TestPlanStatus.Underway.name(), testPlan.getStatus())) {
                status = "进行中";
            } else if (StringUtils.equals(TestPlanStatus.Prepare.name(), testPlan.getStatus())) {
                status = "未开始";
            } else if (StringUtils.equals(TestPlanStatus.Completed.name(), testPlan.getStatus())) {
                status = "已完成";
            }
            context = "测试计划任务通知：" + user.getName() + "创建的" + "'" + testPlan.getName() + "'" + "计划开始时间是:" + "'" + start + "'" + ";" + "计划结束时间是:" + "'" + end + "'" + " " + status;
        } else if (StringUtils.equals(NoticeConstants.Event.DELETE, type)) {
            context = "测试计划任务通知：" + user.getName() + "创建的" + "'" + testPlan.getName() + "'" + "计划开始时间是:" + "'" + start + "'" + ";" + "计划结束时间是:" + "'" + end + "'" + " " + "已删除";
        }
        return context;
    }

    public TestCaseReportMetricDTO getStatisticsMetric(String planId) {
        QueryTestPlanRequest queryTestPlanRequest = new QueryTestPlanRequest();
        queryTestPlanRequest.setId(planId);

        TestPlanDTO testPlan = extTestPlanMapper.list(queryTestPlanRequest).get(0);
        String projectName = getProjectNameByPlanId(planId);
        testPlan.setProjectName(projectName);

        TestCaseReport testCaseReport = testCaseReportMapper.selectByPrimaryKey(testPlan.getReportId());
        JSONObject content = JSONObject.parseObject(testCaseReport.getContent());
        JSONArray componentIds = content.getJSONArray("components");

        List<ReportComponent> components = ReportComponentFactory.createComponents(componentIds.toJavaList(String.class), testPlan);
        List<Issues> issues = buildFunctionalCaseReport(planId, components);
        buildApiCaseReport(planId, components);
        buildScenarioCaseReport(planId, components);

        TestCaseReportMetricDTO testCaseReportMetricDTO = new TestCaseReportMetricDTO();
        components.forEach(component -> {
            component.afterBuild(testCaseReportMetricDTO);
        });
        testCaseReportMetricDTO.setIssues(issues);
        return testCaseReportMetricDTO;
    }

    public void buildApiCaseReport(String planId, List<ReportComponent> components) {
        ApiTestCaseRequest request = new ApiTestCaseRequest();
        request.setPlanId(planId);
        List<TestPlanApiCaseDTO> apiCaseDTOS = testPlanApiCaseService.list(request);
        for (TestPlanApiCaseDTO item : apiCaseDTOS) {
            for (ReportComponent component : components) {
                component.readRecord(item);
            }
        }
    }

    public void buildScenarioCaseReport(String planId, List<ReportComponent> components) {
        TestPlanScenarioRequest request = new TestPlanScenarioRequest();
        request.setPlanId(planId);
        List<ApiScenarioDTO> scenarioDTOS = testPlanScenarioCaseService.list(request);
        for (ApiScenarioDTO item : scenarioDTOS) {
            for (ReportComponent component : components) {
                component.readRecord(item);
            }
        }
    }

    public List<Issues> buildFunctionalCaseReport(String planId, List<ReportComponent> components) {
        IssuesService issuesService = (IssuesService) CommonBeanFactory.getBean("issuesService");
        List<TestPlanCaseDTO> testPlanTestCases = listTestCaseByPlanId(planId);
        List<Issues> issues = new ArrayList<>();
        for (TestPlanCaseDTO testCase : testPlanTestCases) {
            List<Issues> issue = issuesService.getIssues(testCase.getCaseId());
            if (issue.size() > 0) {
                for (Issues i : issue) {
                    i.setModel(testCase.getNodePath());
                    i.setProjectName(testCase.getProjectName());
                    String des = i.getDescription().replaceAll("<p>", "").replaceAll("</p>", "");
                    i.setDescription(des);
                    if (i.getLastmodify() == null || i.getLastmodify() == "") {
                        if (i.getReporter() != null || i.getReporter() != "") {
                            i.setLastmodify(i.getReporter());
                        }
                    }
                }
                issues.addAll(issue);
                Collections.sort(issues, Comparator.comparing(Issues::getCreateTime, (t1, t2) -> t2.compareTo(t1)));
            }
            components.forEach(component -> {
                component.readRecord(testCase);
            });
        }
        return issues;
    }
    public List<TestPlanDTO> selectTestPlanByRelevancy(QueryTestPlanRequest params){
        return  extTestPlanMapper.selectTestPlanByRelevancy(params);
    }
}
