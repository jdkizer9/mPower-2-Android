package org.sagebionetworks.research.mpower.tracking.view_model

import android.content.Context
import android.support.annotation.NonNull
import com.google.common.collect.ImmutableRangeSet
import com.google.common.collect.Range
import com.google.common.collect.RangeSet
import org.sagebionetworks.research.mpower.R
import org.sagebionetworks.research.mpower.tracking.SortUtil
import org.sagebionetworks.research.mpower.tracking.fragment.MedicationLoggingFragment
import org.sagebionetworks.research.mpower.tracking.fragment.TrackingFragment
import org.sagebionetworks.research.mpower.tracking.model.TrackingItem
import org.sagebionetworks.research.mpower.tracking.model.TrackingStepView
import org.sagebionetworks.research.mpower.tracking.recycler_view.MedicationLoggingItem
import org.sagebionetworks.research.mpower.tracking.recycler_view.MedicationLoggingSchedule
import org.sagebionetworks.research.mpower.tracking.recycler_view.MedicationLoggingTitle
import org.sagebionetworks.research.mpower.tracking.view_model.configs.MedicationConfig
import org.sagebionetworks.research.mpower.tracking.view_model.configs.Schedule
import org.sagebionetworks.research.mpower.tracking.view_model.logs.LoggingCollection
import org.sagebionetworks.research.mpower.tracking.view_model.logs.MedicationLog
import org.slf4j.LoggerFactory
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.LocalTime
import org.threeten.bp.format.DateTimeFormatter
import java.util.HashMap

typealias TimeBlock = Pair<String, RangeSet<LocalTime>>

class MedicationTrackingTaskViewModel(context: Context,
        stepView: TrackingStepView,
        previousLoggingCollection: LoggingCollection<MedicationLog>?)
    : TrackingTaskViewModel<MedicationConfig, MedicationLog>(stepView, previousLoggingCollection) {

    private val LOGGER = LoggerFactory.getLogger(MedicationTrackingTaskViewModel::class.java)

    private val timeBlocks: Set<TimeBlock>

    private var previousLoggingCollection: LoggingCollection<MedicationLog>? = null

    init {
        val fiveAM = LocalTime.MIDNIGHT.plusHours(5)
        val fivePM = LocalTime.NOON.plusHours(5)
        val tenPM = LocalTime.NOON.plusHours(10)
        // TODO: move from presentation layer to UI layer; move test from androidTest to test @liujoshua 2018/10/03
        val resources = context.resources
        val morning = resources.getString(R.string.medication_logging_morning_time_block)
        val afternoon = resources.getString(R.string.medication_logging_afternoon_time_block)
        val evening = resources.getString(R.string.medication_logging_evening_time_block)
        val night = resources.getString(R.string.medication_logging_night_time_block)
        timeBlocks = setOf(
                Pair(morning, ImmutableRangeSet.of(Range.closed(fiveAM, LocalTime.NOON))),
                Pair(afternoon, ImmutableRangeSet.of(Range.openClosed(LocalTime.NOON, fivePM))),
                Pair(evening, ImmutableRangeSet.of(Range.openClosed(fivePM, tenPM))),
                Pair(night, ImmutableRangeSet.unionOf(listOf(Range.lessThan(fiveAM), Range.atLeast(tenPM))))
        )
    }

    companion object {

        val MILITARY_TIME_FORMATTER = Schedule.timeOfDayFormatter

        /**
         * Returns 'true' if the given schedule should be logged on the given date, 'false' otherwise.
         * @param schedule the schedule to check whether the user should log on the given day.
         * @param today the day to check whether the user should log the schedule on.
         * @return 'true' if the given schedule should be logged on the given date, 'false' otherwise.
         */
        fun isForToday(schedule: Schedule, today: LocalDate): Boolean {
            if (schedule.isAnytime() || schedule.isDaily()) {
                return true
            }

            val todayString = today.dayOfWeek.value
            return schedule.daysOfWeek.contains(todayString)
        }
    }

    override fun instantiateLoggingCollection(): LoggingCollection<MedicationLog> {
        return LoggingCollection.builder<MedicationLog>()
                .setIdentifier("trackedItems")
                .build()
    }

    override fun instantiateLogForUnloggedItem(config: MedicationConfig): MedicationLog {
        return MedicationLog.builder()
                .setIdentifier(config.identifier)
                .setText(config.identifier)
                .setDosage(config.dosage ?: "")
                .setScheduleItems(config.schedules)
                .build()
    }

    override fun instantiateConfigFromSelection(item: TrackingItem): MedicationConfig {
        loggedElementsById.value?.get(item.identifier)?.let {
            // If we have an existing log, we can make a default config with it's values set
            return instantiateConfigFromLog(it)
        }
        return MedicationConfig.builder()
                .setIdentifier(item.identifier)
                .setSchedules(listOf(Schedule(Schedule.timeOfDayFormatter.format(LocalTime.MIDNIGHT))))
                .build()
    }

    private fun instantiateConfigFromLog(log: MedicationLog): MedicationConfig {
        return MedicationConfig.builder()
                .setIdentifier(log.identifier)
                .setSchedules(log.scheduleItems)
                .setDosage(log.dosage)
                .build()
    }

    /**
     * Returns the list of MedicationLoggingItems that should be displayed for the current time block.
     * A schedule should be displayed if it is anytime, or if it is for today and for the given time block. If a
     * config has a least one schedule a MedicationLoggingTitle will be added for it before all of its schedules.
     * @param today the date to use as the present when calculating which schedules are applicable
     * @return the list of MedicationLoggingItems that should be displayed for the current time block.
     */
    fun getCurrentTimeBlockMedications(timeBlock: TimeBlock, today: LocalDate): List<MedicationLoggingItem> {
        val items: MutableList<MedicationLoggingItem> = mutableListOf()
        val sortedConfigs = SortUtil.getActiveElementsSorted(activeElementsById.value!!)
        for (config in sortedConfigs) {
            val schedules = config.schedules.filter { schedule ->
                schedule.isAnytime()
                        || (isForToday(schedule, today) &&
                        timeBlock.second.contains(schedule.getLocalTimeOfDay() ?: LocalTime.now()))
            }

            if (!schedules.isEmpty()) {
                items.add(MedicationLoggingTitle(config.identifier + " " + config.dosage))
                items.addAll(schedules.map { schedule ->
                    val logDate = applicableLoggedDate(config, schedule, timeBlock.first)
                    MedicationLoggingSchedule(config, schedule, logDate)
                })
            }
        }

        return items
    }

    /**
     * Returns a list of MedicationLoggingItems that should be displayed as the missed medication. A schedule
     * is considered missed if it isn't anytime, and the time block for it has passed, and there isn't a log for it.
     * @param now the time to use as the present when calculating which time block the user is currently in.
     * @return a list of all of the MedicationLoggingItems that should  be displayed as the missed medication.
     */
    fun getMissedMedications(timeBlock: TimeBlock, now: LocalDateTime)
            : List<MedicationLoggingItem> {
        val containingRange = timeBlock.second.asRanges().first { range -> range.contains(now.toLocalTime()) }
        val lowerEndpoint = if (containingRange.hasLowerBound()) containingRange.lowerEndpoint() else LocalTime.MIN
        val missedRange = Range.lessThan(lowerEndpoint)
        val missedItems: MutableList<MedicationLoggingItem> = mutableListOf()
        val sortedConfigs = SortUtil.getActiveElementsSorted(activeElementsById.value!!)
        for (config in sortedConfigs) {
            val missedSchedules = config.schedules.filter { schedule ->
                !schedule.isAnytime() && isForToday(schedule, now.toLocalDate()) &&
                        missedRange.contains(schedule.getLocalTimeOfDay() ?: LocalTime.now()) &&
                        !isLogged(config, schedule, timeBlock.first)
            }

            if (!missedSchedules.isEmpty()) {
                missedItems.add(MedicationLoggingTitle(config.identifier + " " + config.dosage))
                missedItems.addAll(missedSchedules.map { schedule ->
                    val logDate = applicableLoggedDate(config, schedule, timeBlock.first)
                    MedicationLoggingSchedule(config, schedule, logDate)
                })
            }
        }

        return missedItems
    }

    /**
     * Returns a pair containing the name of the current time block as the first, and the RangeSet of the current
     * time block as the second for the given time.
     * @param time The time to get the time block for.
     * @return a pair containing the name of the current time block as the first, and the RangeSet of the current
     * time block as the second for the given time.
     */
    fun getTimeBlock(time: LocalTime): TimeBlock {
        return timeBlocks.first { pair -> pair.second.contains(time) }
    }

    /**
     * Returns 'true' if there is a log representing the given MedicationConfig logged for the given Schedule.
     * 'false' otherwise
     * @param config the MedicationConfig to check for logs for.
     * @param schedule the Schedule to check for a log for.
     * @param timeBlock the string representation of the timeblock the user is currently in
     * (e.g. morning, afternoon, evening, night)
     * @return 'true' if there is a log representing the given MedicationConfig logged for the given Schedule.
     * 'false' otherwise
     */
    private fun isLogged(config: MedicationConfig, schedule: Schedule, timeBlockName: String): Boolean {
        return applicableLoggedDate(config, schedule, timeBlockName) != null
    }

    /**
     * Returns log date if there is a log representing the given MedicationConfig logged for the given Schedule.
     * null otherwise
     * @param config the MedicationConfig to check for logs for.
     * @param schedule the Schedule to check for a log for.
     * @param timeBlock the string representation of the timeblock the user is currently in
     * (e.g. morning, afternoon, evening, night)
     * @return 'true' if there is a log representing the given MedicationConfig logged for the given Schedule.
     * 'false' otherwise
     */
    fun applicableLoggedDate(config: MedicationConfig, schedule: Schedule, timeBlockName: String): Instant? {
        val log = loggedElementsById.value!![config.identifier]
        val expectedTimeOfDay = when {
            schedule.isAnytime() -> timeBlockName
            else -> {
                MILITARY_TIME_FORMATTER.format(schedule.getLocalTimeOfDay())
            }
        }

        val timestamps = log?.timestamps
        return timestamps?.lastOrNull { timestamp -> timestamp.timeOfDay == expectedTimeOfDay }?.loggedDate
    }

    override fun proceedToInitialFragmentOnSecondRun(trackingFragment: TrackingFragment<*, *, *>) {
        trackingFragment.replaceWithFragment(MedicationLoggingFragment.newInstance(this.stepView))
    }

    override fun setupModelFromPreviousCollection(previousLoggingCollection: LoggingCollection<MedicationLog>) {
        // For previous med logging collections, we need to pass more information to create the config
        val activeElements = HashMap<String, MedicationConfig>()
        for (log in previousLoggingCollection.items) {
            activeElements[log.identifier] = instantiateConfigFromLog(log)
        }
        activeElementsById.value = activeElements
    }
}

