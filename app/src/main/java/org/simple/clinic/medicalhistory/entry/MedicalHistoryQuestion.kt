package org.simple.clinic.medicalhistory.entry

import android.support.annotation.StringRes
import android.support.v7.util.DiffUtil
import org.simple.clinic.medicalhistory.MedicalHistory

data class MedicalHistoryQuestion(
    @StringRes val questionStringRes: Int,
    val answer: Answer,
    val setter: (MedicalHistory) -> MedicalHistory
) {

  enum class Answer {
    YES,
    NO,
    UNSELECTED
  }

  class ItemDiffer : DiffUtil.ItemCallback<MedicalHistoryQuestion>() {
    override fun areItemsTheSame(old: MedicalHistoryQuestion, new: MedicalHistoryQuestion) = old.questionStringRes == new.questionStringRes
    override fun areContentsTheSame(old: MedicalHistoryQuestion, new: MedicalHistoryQuestion) = old == new
  }
}

