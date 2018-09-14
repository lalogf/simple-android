package org.simple.clinic.medicalhistory.entry

import android.support.v7.recyclerview.extensions.ListAdapter
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import kotterknife.bindView
import org.simple.clinic.R
import org.simple.clinic.medicalhistory.entry.MedicalHistoryQuestion.Answer.NO
import org.simple.clinic.medicalhistory.entry.MedicalHistoryQuestion.Answer.YES
import javax.inject.Inject

class MedicalHistoryQuestionsAdapter @Inject constructor() :
    ListAdapter<MedicalHistoryQuestion, QuestionViewHolder>(MedicalHistoryQuestion.ItemDiffer()) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestionViewHolder {
    val itemView = LayoutInflater.from(parent.context).inflate(R.layout.list_medical_history_question, parent, false)
    return QuestionViewHolder(itemView)
  }

  override fun onBindViewHolder(holder: QuestionViewHolder, position: Int) {
    holder.question = getItem(position)
    holder.render()
  }
}

class QuestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

  private val questionTextView by bindView<TextView>(R.id.medicalhistoryquestion_question)
  private val yesRadioButton by bindView<RadioButton>(R.id.medicalhistoryquestion_yes)
  private val noRadioButton by bindView<RadioButton>(R.id.medicalhistoryquestion_no)

  lateinit var question: MedicalHistoryQuestion

  fun render() {
    questionTextView.setText(question.questionStringRes)
    yesRadioButton.isChecked = question.answer == YES
    noRadioButton.isChecked = question.answer == NO
  }
}
