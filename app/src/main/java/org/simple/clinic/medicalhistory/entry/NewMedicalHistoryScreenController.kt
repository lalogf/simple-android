package org.simple.clinic.medicalhistory.entry

import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.ObservableTransformer
import io.reactivex.rxkotlin.ofType
import org.simple.clinic.widgets.ScreenCreated
import org.simple.clinic.widgets.UiEvent
import javax.inject.Inject

typealias Ui = NewMedicalHistoryScreen
typealias UiChange = (Ui) -> Unit

class NewMedicalHistoryScreenController @Inject constructor() : ObservableTransformer<UiEvent, UiChange> {

  override fun apply(upstream: Observable<UiEvent>): ObservableSource<UiChange> {
    return Observable.empty()
  }

  private fun populateQuestions(events: Observable<UiEvent>): Observable<UiChange> {
    events
        .ofType<ScreenCreated>()
  }
}
