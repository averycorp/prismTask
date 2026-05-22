  1585
  1586	    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  1587	    // Dispatch across every synced entityType — see pushCreate for the same
  1588	    // trade-off. TODO: refactor pushUpdate to reduce early return statements.
  1589	    private suspend fun pushUpdate(meta: SyncMetadataEntity) {
  1590	        if (meta.cloudId.isEmpty()) {
  1591	            pushCreate(meta)
  1592	            return
  1593	        }
  1594	        val docRef = userCollection(collectionNameFor(meta.entityType))?.document(meta.cloudId) ?: return
  1595	        val data = when (meta.entityType) {
  1596	            "task" -> {
  1597	                val task = taskDao.getTaskByIdOnce(meta.localId) ?: return
  1598	                val tagIds = tagDao.getTagIdsForTaskOnce(task.id).mapNotNull { syncMetadataDao.getCloudId(it, "tag") }
  1599	                val projectCloudId = task.projectId?.let { syncMetadataDao.getCloudId(it, "project") }
  1600	                val parentTaskCloudId = task.parentTaskId?.let { syncMetadataDao.getCloudId(it, "task") }
  1601	                val sourceHabitCloudId = task.sourceHabitId?.let { syncMetadataDao.getCloudId(it, "habit") }
  1602	                val phaseCloudId = task.phaseId?.let { syncMetadataDao.getCloudId(it, "project_phase") }
  1603	                SyncMapper.taskToMap(task, tagIds, projectCloudId, parentTaskCloudId, sourceHabitCloudId, phaseCloudId)
  1604	            }
  1605	            "project" -> {
  1606	                val project = projectDao.getProjectByIdOnce(meta.localId) ?: return
  1607	                SyncMapper.projectToMap(project)
  1608	            }
  1609	            "tag" -> {
  1610	                val tag = tagDao.getTagByIdOnce(meta.localId) ?: return
  1611	                SyncMapper.tagToMap(tag)
  1612	            }
  1613	            "habit" -> {
  1614	                val habit = habitDao.getHabitByIdOnce(meta.localId) ?: return
  1615	                SyncMapper.habitToMap(habit)
  1616	            }
  1617	            "task_template" -> {
  1618	                val template = taskTemplateDao.getTemplateById(meta.localId) ?: return
  1619	                val templateProjectCloudId = template.templateProjectId?.let { syncMetadataDao.getCloudId(it, "project") }
  1620	                SyncMapper.taskTemplateToMap(template, templateProjectCloudId)
  1621	            }
  1622	            "course" -> {
  1623	                val course = schoolworkDao.getCourseById(meta.localId) ?: return
  1624	                SyncMapper.courseToMap(course)
  1625	            }
  1626	            "course_completion" -> {
  1627	                val completion = schoolworkDao.getAllCompletionsOnce().find { it.id == meta.localId } ?: return
  1628	                val courseCloudId = syncMetadataDao.getCloudId(completion.courseId, "course") ?: return
  1629	                SyncMapper.courseCompletionToMap(completion, courseCloudId)
  1630	            }
  1631	            "self_care_step" -> {
  1632	                val step = selfCareDao.getAllStepsOnce().find { it.id == meta.localId } ?: return
  1633	                SyncMapper.selfCareStepToMap(step)
  1634	            }
  1635	            "self_care_log" -> {
  1636	                val log = selfCareDao.getAllLogsOnce().find { it.id == meta.localId } ?: return
  1637	                SyncMapper.selfCareLogToMap(log)
  1638	            }
  1639	            "medication" -> {
  1640	                val med = medicationDao.getByIdOnce(meta.localId) ?: return
  1641	                val slotCloudIds = medicationSlotDao.getSlotIdsForMedicationOnce(med.id)
  1642	                    .mapNotNull { syncMetadataDao.getCloudId(it, "medication_slot") }
  1643	                MedicationSyncMapper.medicationToMap(med, slotCloudIds)
  1644	            }
  1645	            "medication_dose" -> {
  1646	                val dose = medicationDoseDao.getAllOnce().find { it.id == meta.localId } ?: return
  1647	                val medCloudId = if (dose.medicationId == null) {
  1648	                    null
  1649	                } else {
  1650	                    syncMetadataDao.getCloudId(dose.medicationId, "medication") ?: return
  1651	                }
  1652	                MedicationSyncMapper.medicationDoseToMap(dose, medCloudId)
  1653	            }
  1654	            "medication_slot" -> {
  1655	                val slot = medicationSlotDao.getByIdOnce(meta.localId) ?: return
  1656	                MedicationSyncMapper.medicationSlotToMap(slot)
  1657	            }
  1658	            "medication_slot_override" -> {
  1659	                val override = medicationSlotOverrideDao.getByIdOnce(meta.localId) ?: return
  1660	                val medCloudId = syncMetadataDao.getCloudId(override.medicationId, "medication") ?: return
  1661	                val slotCloudId = syncMetadataDao.getCloudId(override.slotId, "medication_slot") ?: return
  1662	                MedicationSyncMapper.medicationSlotOverrideToMap(override, medCloudId, slotCloudId)
  1663	            }
  1664	            "medication_tier_state" -> {
  1665	                val state = medicationTierStateDao.getByIdOnce(meta.localId) ?: return
  1666	                val medCloudId = syncMetadataDao.getCloudId(state.medicationId, "medication") ?: return
  1667	                val slotCloudId = syncMetadataDao.getCloudId(state.slotId, "medication_slot") ?: return
  1668	                MedicationSyncMapper.medicationTierStateToMap(state, medCloudId, slotCloudId)
  1669	            }
  1670	            "notification_profile" -> {
  1671	                val profile = notificationProfileDao.getById(meta.localId) ?: return
  1672	                SyncMapper.notificationProfileToMap(profile)
  1673	            }
  1674	            "custom_sound" -> {
  1675	                val sound = customSoundDao.getById(meta.localId) ?: return
  1676	                SyncMapper.customSoundToMap(sound)
  1677	            }
  1678	            "saved_filter" -> {
  1679	                val filter = savedFilterDao.getByIdOnce(meta.localId) ?: return
  1680	                SyncMapper.savedFilterToMap(filter)
  1681	            }
  1682	            "nlp_shortcut" -> {
  1683	                val shortcut = nlpShortcutDao.getByIdOnce(meta.localId) ?: return
  1684	                SyncMapper.nlpShortcutToMap(shortcut)
  1685	            }
  1686	            "habit_template" -> {
  1687	                val template = habitTemplateDao.getById(meta.localId) ?: return
  1688	                SyncMapper.habitTemplateToMap(template)
  1689	            }
  1690	            "project_template" -> {
  1691	                val template = projectTemplateDao.getById(meta.localId) ?: return
  1692	                SyncMapper.projectTemplateToMap(template)
  1693	            }
  1694	            "boundary_rule" -> {
  1695	                val rule = boundaryRuleDao.getByIdOnce(meta.localId) ?: return
  1696	                SyncMapper.boundaryRuleToMap(rule)
  1697	            }
  1698	            "automation_rule" -> {
  1699	                val rule = automationRuleDao.getByIdOnce(meta.localId) ?: return
  1700	                SyncMapper.automationRuleToMap(rule)
  1701	            }
  1702	            "check_in_log" -> {
  1703	                val log = checkInLogDao.getByIdOnce(meta.localId) ?: return
  1704	                SyncMapper.checkInLogToMap(log)
  1705	            }
  1706	            "mood_energy_log" -> {
  1707	                val log = moodEnergyLogDao.getByIdOnce(meta.localId) ?: return
  1708	                SyncMapper.moodEnergyLogToMap(log)
  1709	            }
  1710	            "focus_release_log" -> {
  1711	                val log = focusReleaseLogDao.getByIdOnce(meta.localId) ?: return
  1712	                val taskCloudId = log.taskId?.let { syncMetadataDao.getCloudId(it, "task") }
  1713	                SyncMapper.focusReleaseLogToMap(log, taskCloudId)
  1714	            }
  1715	            "medication_refill" -> {
  1716	                val refill = medicationRefillDao.getByIdOnce(meta.localId) ?: return
  1717	                SyncMapper.medicationRefillToMap(refill)
  1718	            }
  1719	            "weekly_review" -> {
  1720	                val review = weeklyReviewDao.getByIdOnce(meta.localId) ?: return
  1721	                SyncMapper.weeklyReviewToMap(review)
  1722	            }
  1723	            // `daily_essential_slot_completion` Firestore push removed
  1724	            // in parity Batch 5 PR-9 (decision D-E4). BackendSyncService
  1725	            // is authoritative.
  1726	            "assignment" -> {
  1727	                val assignment = schoolworkDao.getAssignmentById(meta.localId) ?: return
  1728	                val courseCloudId = syncMetadataDao.getCloudId(assignment.courseId, "course")
  1729	                    ?: return
  1730	                SyncMapper.assignmentToMap(assignment, courseCloudId)
  1731	            }
  1732	            "attachment" -> {
  1733	                val attachment = attachmentDao.getByIdOnce(meta.localId) ?: return
  1734	                val taskCloudId = syncMetadataDao.getCloudId(attachment.taskId, "task")
  1735	                    ?: return
  1736	                SyncMapper.attachmentToMap(attachment, taskCloudId)
  1737	            }
  1738	            "study_log" -> {
  1739	                val log = schoolworkDao.getStudyLogByIdOnce(meta.localId) ?: return
  1740	                val coursePickCloudId = log.coursePick?.let { syncMetadataDao.getCloudId(it, "course") }
  1741	                val assignmentPickCloudId = log.assignmentPick?.let {
  1742	                    syncMetadataDao.getCloudId(it, "assignment")
  1743	                }
  1744	                SyncMapper.studyLogToMap(log, coursePickCloudId, assignmentPickCloudId)
  1745	            }
  1746	            "project_phase" -> {
  1747	                val phase = projectPhaseDao.getByIdOnce(meta.localId) ?: return
  1748	                val projectCloudId = syncMetadataDao.getCloudId(phase.projectId, "project")
  1749	                    ?: return
  1750	                SyncMapper.projectPhaseToMap(phase, projectCloudId)
  1751	            }
  1752	            "project_risk" -> {
  1753	                val risk = projectRiskDao.getByIdOnce(meta.localId) ?: return
  1754	                val projectCloudId = syncMetadataDao.getCloudId(risk.projectId, "project")
  1755	                    ?: return
  1756	                SyncMapper.projectRiskToMap(risk, projectCloudId)
  1757	            }
  1758	            "task_dependency" -> {
  1759	                val dep = taskDependencyDao.getByIdOnce(meta.localId) ?: return
  1760	                val blockerCloudId =
  1761	                    syncMetadataDao.getCloudId(dep.blockerTaskId, "task") ?: return
  1762	                val blockedCloudId =
  1763	                    syncMetadataDao.getCloudId(dep.blockedTaskId, "task") ?: return
  1764	                SyncMapper.taskDependencyToMap(dep, blockerCloudId, blockedCloudId)
  1765	            }
  1766	            "external_anchor" -> {
  1767	                val anchor = externalAnchorDao.getByIdOnce(meta.localId) ?: return
  1768	                val projectCloudId = syncMetadataDao.getCloudId(anchor.projectId, "project")
  1769	                    ?: return
  1770	                val phaseCloudId = anchor.phaseId?.let {
  1771	                    syncMetadataDao.getCloudId(it, "project_phase")
  1772	                }
  1773	                SyncMapper.externalAnchorToMap(anchor, projectCloudId, phaseCloudId)
  1774	            }
  1775	            else -> return
  1776	        }
