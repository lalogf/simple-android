package org.simple.clinic.medicalhistory.entry

import android.content.Context
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.Toolbar
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import com.jakewharton.rxbinding2.view.RxView
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotterknife.bindView
import org.simple.clinic.R
import org.simple.clinic.activity.TheActivity
import org.simple.clinic.router.screen.ScreenRouter
import org.simple.clinic.widgets.ScreenCreated
import java.util.UUID
import javax.inject.Inject

class NewMedicalHistoryScreen(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

  companion object {
    val KEY: (patientUuid: UUID) -> NewMedicalHistoryScreenKey = ::NewMedicalHistoryScreenKey
  }

  @Inject
  lateinit var controller: NewMedicalHistoryScreenController

  @Inject
  lateinit var screenRouter: ScreenRouter

  @Inject
  lateinit var adapter: MedicalHistoryQuestionsAdapter

  private val toolbar by bindView<Toolbar>(R.id.newmedicalhistory_toolbar)
  private val recyclerView by bindView<RecyclerView>(R.id.newmedicalhistory_questions)
  private val saveButton by bindView<Button>(R.id.newmedicalhistory_save)

  override fun onFinishInflate() {
    super.onFinishInflate()
    if (isInEditMode) {
      return
    }
    TheActivity.component.inject(this)

    toolbar.setNavigationOnClickListener {
      screenRouter.pop()
    }

    recyclerView.layoutManager = LinearLayoutManager(context)
    recyclerView.adapter = adapter

    Observable.mergeArray(screenCreates())
        .observeOn(Schedulers.io())
        .compose(controller)
        .observeOn(AndroidSchedulers.mainThread())
        .takeUntil(RxView.detaches(this))
        .subscribe { uiChange -> uiChange(this) }
  }

  private fun screenCreates() = Observable.just(ScreenCreated())
}
