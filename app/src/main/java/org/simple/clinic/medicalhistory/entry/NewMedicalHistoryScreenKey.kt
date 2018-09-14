package org.simple.clinic.medicalhistory.entry

import android.os.Parcelable
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import org.simple.clinic.R
import org.simple.clinic.router.screen.FullScreenKey
import java.util.UUID

@Parcelize
data class NewMedicalHistoryScreenKey(val patientUuid: UUID) : FullScreenKey, Parcelable {

  @IgnoredOnParcel
  override val analyticsName = "New Patient Medical History"

  override fun layoutRes(): Int {
    return R.layout.screen_new_patient_medical_history
  }
}

