package com.kawaiipet.app.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PetAnimationController @Inject constructor() {

    private val _expression = MutableStateFlow(PetExpression.SLEEPING)
    val expression: StateFlow<PetExpression> = _expression.asStateFlow()

    private val _mouthOpen = MutableStateFlow(false)
    val mouthOpen: StateFlow<Boolean> = _mouthOpen.asStateFlow()

    fun setExpression(expression: PetExpression) {
        _expression.value = expression
    }

    fun setMouthOpen(open: Boolean) {
        _mouthOpen.value = open
    }

    fun getCurrentExpression(): PetExpression = _expression.value
}
