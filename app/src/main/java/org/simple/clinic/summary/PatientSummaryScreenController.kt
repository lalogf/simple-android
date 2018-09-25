package org.simple.clinic.summary

import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.ObservableTransformer
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.ofType
import io.reactivex.rxkotlin.withLatestFrom
import org.simple.clinic.ReportAnalyticsEvents
import org.simple.clinic.analytics.Analytics
import org.simple.clinic.bp.BloodPressureRepository
import org.simple.clinic.drugs.PrescriptionRepository
import org.simple.clinic.medicalhistory.MedicalHistoryRepository
import org.simple.clinic.patient.PatientRepository
import org.simple.clinic.summary.PatientSummaryCaller.NEW_PATIENT
import org.simple.clinic.summary.PatientSummaryCaller.SEARCH
import org.simple.clinic.util.Just
import org.simple.clinic.widgets.UiEvent
import javax.inject.Inject

typealias Ui = PatientSummaryScreen
typealias UiChange = (Ui) -> Unit

class PatientSummaryScreenController @Inject constructor(
    private val patientRepository: PatientRepository,
    private val bpRepository: BloodPressureRepository,
    private val prescriptionRepository: PrescriptionRepository,
    private val medicalHistoryRepository: MedicalHistoryRepository,
    private val timestampGenerator: RelativeTimestampGenerator
) : ObservableTransformer<UiEvent, UiChange> {

  override fun apply(events: Observable<UiEvent>): ObservableSource<UiChange> {
    val replayedEvents = events.compose(ReportAnalyticsEvents()).replay().refCount()

    return Observable.mergeArray(
        reportViewedPatientEvent(replayedEvents),
        populatePatientProfile(replayedEvents),
        constructListDataSet(replayedEvents),
        openBloodPressureBottomSheet(replayedEvents),
        openPrescribedDrugsScreen(replayedEvents),
        handleBackAndDoneClicks(replayedEvents),
        exitScreenAfterSchedulingAppointment(replayedEvents))
  }

  private fun reportViewedPatientEvent(events: Observable<UiEvent>): Observable<UiChange> {
    return events.ofType<PatientSummaryScreenCreated>()
        .take(1L)
        .doOnNext { (patientUuid, caller) -> Analytics.reportViewedPatient(patientUuid, caller.name) }
        .flatMap { Observable.empty<UiChange>() }
  }

  private fun populatePatientProfile(events: Observable<UiEvent>): Observable<UiChange> {
    val patientUuid = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.patientUuid }

    val sharedPatients = patientUuid
        .flatMap { patientRepository.patient(it) }
        .map {
          // We do not expect the patient to get deleted while this screen is already open.
          (it as Just).value
        }
        .replay(1)
        .refCount()

    val addresses = sharedPatients
        .flatMap { patient -> patientRepository.address(patient.addressUuid) }
        .map { (it as Just).value }

    val phoneNumbers = patientUuid
        .flatMap { patientRepository.phoneNumbers(it) }

    return Observables.combineLatest(sharedPatients, addresses, phoneNumbers)
        .map { (patient, address, phoneNumber) -> { ui: Ui -> ui.populatePatientProfile(patient, address, phoneNumber) } }
  }

  private fun constructListDataSet(events: Observable<UiEvent>): Observable<UiChange> {
    val patientUuid = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.patientUuid }

    val prescriptionItems = patientUuid
        .flatMap { prescriptionRepository.newestPrescriptionsForPatient(it) }
        .map(::SummaryPrescribedDrugsItem)

    val bloodPressureItems = patientUuid
        .flatMap { bpRepository.newest100MeasurementsForPatient(it) }
        .map { measurements ->
          measurements.map { measurement ->
            val timestamp = timestampGenerator.generate(measurement.updatedAt)
            SummaryBloodPressureListItem(measurement, timestamp)
          }
        }

    val medicalHistoryItems = patientUuid
        .flatMap { medicalHistoryRepository.historyForPatient(it) }
        .map { history ->
          val lastSyncTimestamp = timestampGenerator.generate(history.updatedAt)
          SummaryMedicalHistoryItem(history, lastSyncTimestamp)
        }

    // combineLatest() is important here so that the first data-set for the list
    // is dispatched in one go instead of them appearing one after another on the UI.
    return Observables.combineLatest(prescriptionItems, bloodPressureItems, medicalHistoryItems)
        .map { (prescriptions, bp, history) ->
          { ui: Ui ->
            ui.populateList(prescriptions, bp, history)
          }
        }
  }

  private fun openBloodPressureBottomSheet(events: Observable<UiEvent>): Observable<UiChange> {
    val patientUuid = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.patientUuid }

    val autoShows = events
        .ofType<PatientSummaryScreenCreated>()
        .filter { it.caller == NEW_PATIENT }
        .withLatestFrom(patientUuid)
        .map { (_, patientUuid) -> { ui: Ui -> ui.showBloodPressureEntrySheetIfNotShownAlready(patientUuid) } }

    val newBpClicks = events
        .ofType<PatientSummaryNewBpClicked>()
        .withLatestFrom(patientUuid)
        .map { (_, patientUuid) -> { ui: Ui -> ui.showBloodPressureEntrySheet(patientUuid) } }

    return autoShows.mergeWith(newBpClicks)
  }

  private fun openPrescribedDrugsScreen(events: Observable<UiEvent>): Observable<UiChange> {
    val patientUuid = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.patientUuid }

    return events
        .ofType<PatientSummaryUpdateDrugsClicked>()
        .withLatestFrom(patientUuid)
        .map { (_, patientUuid) -> { ui: Ui -> ui.showUpdatePrescribedDrugsScreen(patientUuid) } }
  }

  private fun handleBackAndDoneClicks(events: Observable<UiEvent>): Observable<UiChange> {
    val callers = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.caller }

    val patientUuids = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.patientUuid }

    val bloodPressureSaves = events
        .ofType<PatientSummaryBloodPressureClosed>()
        .startWith(PatientSummaryBloodPressureClosed(false))
        .map { it.wasBloodPressureSaved }

    val backClicks = events
        .ofType<PatientSummaryBackClicked>()

    val doneClicks = events
        .ofType<PatientSummaryDoneClicked>()

    val doneOrBackClicksWithBpSaved = Observable.merge(doneClicks, backClicks)
        .withLatestFrom(bloodPressureSaves, patientUuids)
        .filter { (_, saved, _) -> saved }
        .map { (_, _, uuid) -> { ui: Ui -> ui.showScheduleAppointmentSheet(patientUuid = uuid) } }

    val backClicksWithBpNotSaved = backClicks
        .withLatestFrom(bloodPressureSaves, callers)
        .filter { (_, saved, _) -> saved.not() }
        .map { (_, _, caller) ->
          { ui: Ui ->
            when (caller!!) {
              SEARCH -> ui.goBackToPatientSearch()
              NEW_PATIENT -> ui.goBackToHome()
            }.exhaustive()
          }
        }

    val doneClicksWithBpNotSaved = doneClicks
        .withLatestFrom(bloodPressureSaves)
        .filter { (_, saved) -> saved.not() }
        .map { { ui: Ui -> ui.goBackToHome() } }

    return Observable.mergeArray(
        doneOrBackClicksWithBpSaved,
        backClicksWithBpNotSaved,
        doneClicksWithBpNotSaved)
  }

  private fun exitScreenAfterSchedulingAppointment(events: Observable<UiEvent>): Observable<UiChange> {
    val callers = events
        .ofType<PatientSummaryScreenCreated>()
        .map { it.caller }

    val scheduleAppointmentCloses = events
        .ofType<ScheduleAppointmentSheetClosed>()

    val backClicks = events
        .ofType<PatientSummaryBackClicked>()

    val doneClicks = events
        .ofType<PatientSummaryDoneClicked>()

    val afterBackClicks = scheduleAppointmentCloses
        .withLatestFrom(backClicks, callers)
        .map { (_, _, caller) ->
          { ui: Ui ->
            when (caller!!) {
              SEARCH -> ui.goBackToPatientSearch()
              NEW_PATIENT -> ui.goBackToHome()
            }.exhaustive()
          }
        }

    val afterDoneClicks = scheduleAppointmentCloses
        .withLatestFrom(doneClicks)
        .map { { ui: Ui -> ui.goBackToHome() } }

    return afterBackClicks.mergeWith(afterDoneClicks)
  }
}

// Forces when blocks to be exhaustive.
private fun Unit.exhaustive() {}
