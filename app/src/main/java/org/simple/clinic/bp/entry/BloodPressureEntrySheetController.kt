package org.simple.clinic.bp.entry

import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.ObservableTransformer
import io.reactivex.rxkotlin.ofType
import io.reactivex.rxkotlin.withLatestFrom
import org.simple.clinic.ReportAnalyticsEvents
import org.simple.clinic.analytics.Analytics
import org.simple.clinic.bp.BloodPressureRepository
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_DIASTOLIC_EMPTY
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_DIASTOLIC_TOO_HIGH
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_DIASTOLIC_TOO_LOW
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_SYSTOLIC_EMPTY
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_SYSTOLIC_LESS_THAN_DIASTOLIC
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_SYSTOLIC_TOO_HIGH
import org.simple.clinic.bp.entry.BloodPressureValidation.ERROR_SYSTOLIC_TOO_LOW
import org.simple.clinic.bp.entry.BloodPressureValidation.SUCCESS
import org.simple.clinic.bp.entry.OpenAs.NEW_BP
import org.simple.clinic.bp.entry.OpenAs.UPDATE_BP
import org.simple.clinic.util.exhaustive
import org.simple.clinic.widgets.UiEvent
import javax.inject.Inject

typealias Ui = BloodPressureEntrySheet
typealias UiChange = (Ui) -> Unit

class BloodPressureEntrySheetController @Inject constructor(
    private val bloodPressureRepository: BloodPressureRepository
) : ObservableTransformer<UiEvent, UiChange> {

  override fun apply(events: Observable<UiEvent>): ObservableSource<UiChange> {
    val replayedEvents = events.compose(ReportAnalyticsEvents()).replay().refCount()

    val transformedEvents = replayedEvents
        .mergeWith(validateBp(events))

    return Observable.mergeArray(
        automaticDiastolicFocusChanges(transformedEvents),
        validationErrorResets(transformedEvents),
        preFillWhenUpdatingABloodPressure(transformedEvents),
        showValidationErrors(transformedEvents),
        reportValidationErrors(transformedEvents),
        saveNewBp(transformedEvents),
        updateBp(transformedEvents))
  }

  private fun automaticDiastolicFocusChanges(events: Observable<UiEvent>): Observable<UiChange> {
    return events.ofType<BloodPressureSystolicTextChanged>()
        .filter { shouldFocusDiastolic(it.systolic) }
        .distinctUntilChanged()
        .map { { ui: Ui -> ui.changeFocusToDiastolic() } }
  }

  private fun shouldFocusDiastolic(systolicText: String): Boolean {
    return (systolicText.length == 3 && systolicText.matches("^[123].*$".toRegex()))
        || (systolicText.length == 2 && systolicText.matches("^[789].*$".toRegex()))
  }

  private fun validationErrorResets(events: Observable<UiEvent>): Observable<UiChange> {
    val systolicChanges = events.ofType<BloodPressureSystolicTextChanged>()
        .distinctUntilChanged()
        .map { { ui: Ui -> ui.hideErrorMessage() } }

    val diastolicChanges = events.ofType<BloodPressureDiastolicTextChanged>()
        .distinctUntilChanged()
        .map { { ui: Ui -> ui.hideErrorMessage() } }

    return Observable.merge(systolicChanges, diastolicChanges)
  }

  private fun preFillWhenUpdatingABloodPressure(events: Observable<UiEvent>): Observable<UiChange> {
    return events
        .ofType<BloodPressureEntrySheetCreated>()
        .filter { it.openAs == UPDATE_BP }
        .flatMapSingle { bloodPressureRepository.findOne(it.uuid) }
        .map { bloodPressure -> { ui: Ui -> ui.updateBpMeasurements(bloodPressure.systolic, bloodPressure.diastolic) } }
  }

  private fun validateBp(events: Observable<UiEvent>): Observable<UiEvent> {
    val imeDoneClicks = events.ofType<BloodPressureSaveClicked>()

    val systolicChanges = events
        .ofType<BloodPressureSystolicTextChanged>()
        .map { it.systolic }

    val diastolicChanges = events
        .ofType<BloodPressureDiastolicTextChanged>()
        .map { it.diastolic }

    return imeDoneClicks
        .withLatestFrom(systolicChanges, diastolicChanges)
        .map { (_, systolic, diastolic) -> BloodPressureEntry(systolic, diastolic) }
        .distinctUntilChanged()
        .map { bp -> BloodPressureValidated(validateInput(bp), bp) }
  }

  private fun showValidationErrors(events: Observable<UiEvent>): Observable<UiChange> {
    return events
        .ofType<BloodPressureValidated>()
        .map {
          { ui: Ui ->
            when (it.validation) {
              ERROR_SYSTOLIC_LESS_THAN_DIASTOLIC -> ui.showSystolicLessThanDiastolicError()
              ERROR_SYSTOLIC_TOO_HIGH -> ui.showSystolicHighError()
              ERROR_SYSTOLIC_TOO_LOW -> ui.showSystolicLowError()
              ERROR_DIASTOLIC_TOO_HIGH -> ui.showDiastolicHighError()
              ERROR_DIASTOLIC_TOO_LOW -> ui.showDiastolicLowError()
              ERROR_SYSTOLIC_EMPTY -> ui.showSystolicEmptyError()
              ERROR_DIASTOLIC_EMPTY -> ui.showDiastolicEmptyError()
              SUCCESS -> {
                // Nothing to do here, SUCCESS handled below separately!
              }
            }.exhaustive()
          }
        }
  }

  private fun reportValidationErrors(events: Observable<UiEvent>): Observable<UiChange> {
    return events
        .ofType<BloodPressureValidated>()
        .flatMap {
          Analytics.reportInputValidationError(it.analyticsName)
          Observable.empty<UiChange>()
        }
  }

  private fun saveNewBp(events: Observable<UiEvent>): Observable<UiChange> {
    val patientUuidStream = events
        .ofType<BloodPressureEntrySheetCreated>()
        .filter { it.openAs == NEW_BP }
        .map { it.uuid }

    return events
        .ofType<BloodPressureValidated>()
        .filter { it.validation == SUCCESS }
        .map { it.bloodPressure }
        .withLatestFrom(patientUuidStream)
        .flatMapSingle { (bp, patientUuid) ->
          bloodPressureRepository
              .saveMeasurement(patientUuid = patientUuid, systolic = bp.systolic.toInt(), diastolic = bp.diastolic.toInt())
              .map { { ui: Ui -> ui.setBPSavedResultAndFinish() } }
        }
  }

  private fun updateBp(events: Observable<UiEvent>): Observable<UiChange> {
    val savedMeasurementStream = events
        .ofType<BloodPressureEntrySheetCreated>()
        .filter { it.openAs == UPDATE_BP }
        .flatMapSingle { bloodPressureRepository.findOne(it.uuid) }
        .take(1)

    return events
        .ofType<BloodPressureValidated>()
        .filter { it.validation == SUCCESS }
        .map { it.bloodPressure }
        .withLatestFrom(savedMeasurementStream)
        .flatMapSingle { (newBp, savedMeasurement) ->
          val updatedMeasurement = savedMeasurement.copy(systolic = newBp.systolic.toInt(), diastolic = newBp.diastolic.toInt())

          bloodPressureRepository
              .updateMeasurement(updatedMeasurement)
              .toSingleDefault({ ui: Ui -> ui.setBPSavedResultAndFinish() })
        }
  }

  private fun validateInput(bloodPressure: BloodPressureEntry): BloodPressureValidation {
    if (bloodPressure.systolic.isBlank()) {
      return ERROR_SYSTOLIC_EMPTY
    }
    if (bloodPressure.diastolic.isBlank()) {
      return ERROR_DIASTOLIC_EMPTY
    }

    val systolicNumber = bloodPressure.systolic.trim().toInt()
    val diastolicNumber = bloodPressure.diastolic.trim().toInt()

    return when {
      systolicNumber < 70 -> ERROR_SYSTOLIC_TOO_LOW
      systolicNumber > 300 -> ERROR_SYSTOLIC_TOO_HIGH
      diastolicNumber < 40 -> ERROR_DIASTOLIC_TOO_LOW
      diastolicNumber > 180 -> ERROR_DIASTOLIC_TOO_HIGH
      systolicNumber < diastolicNumber -> ERROR_SYSTOLIC_LESS_THAN_DIASTOLIC
      else -> SUCCESS
    }
  }
}
