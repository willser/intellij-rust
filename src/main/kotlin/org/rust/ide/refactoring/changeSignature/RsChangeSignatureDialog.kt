/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.refactoring.changeSignature

import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapiext.isUnitTestMode
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.changeSignature.*
import com.intellij.refactoring.ui.ComboBoxVisibilityPanel
import com.intellij.ui.components.CheckBox
import com.intellij.ui.layout.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Consumer
import org.jetbrains.annotations.TestOnly
import org.rust.ide.presentation.render
import org.rust.ide.refactoring.isValidRustVariableIdentifier
import org.rust.lang.RsFileType
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.RsTypeReferenceCodeFragment
import org.rust.lang.core.psi.RsVis
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.returnType
import org.rust.lang.core.types.ty.Ty
import org.rust.lang.core.types.ty.TyUnit
import org.rust.lang.core.types.type
import org.rust.openapiext.document
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

typealias ChangeFunctionSignatureMock = (config: RsChangeFunctionSignatureConfig) -> Unit

private var MOCK: ChangeFunctionSignatureMock? = null

fun showChangeFunctionSignatureDialog(
    project: Project,
    config: RsChangeFunctionSignatureConfig
) {
    if (isUnitTestMode) {
        val mock = MOCK ?: error("You should set mock UI via `withMockChangeFunctionSignature`")
        mock(config)
        RsChangeSignatureProcessor(project, config.createChangeInfo()).run()
    } else {
        ChangeSignatureDialog(project, SignatureDescriptor(config)).show()
    }
}

@TestOnly
fun withMockChangeFunctionSignature(mock: ChangeFunctionSignatureMock, action: () -> Unit) {
    MOCK = mock
    try {
        action()
    } finally {
        MOCK = null
    }
}

private class SignatureParameter(val factory: RsPsiFactory, val parameter: Parameter) : ParameterInfo {
    override fun getName(): String = parameter.patText
    override fun getOldIndex(): Int = parameter.index
    override fun getDefaultValue(): String? = null
    override fun setName(name: String?) {
        if (name != null) {
            val pat = factory.tryCreatePat(name) ?: return
            parameter.pat = pat
        }
    }

    override fun getTypeText(): String = parameter.type.render(
        includeLifetimeArguments = true,
        includeTypeArguments = true
    )

    override fun isUseAnySingleVariable(): Boolean = false
    override fun setUseAnySingleVariable(b: Boolean) {}
}

private class SignatureDescriptor(val config: RsChangeFunctionSignatureConfig)
    : MethodDescriptor<SignatureParameter, String> {
    val function: RsFunction = config.function

    override fun getName(): String = config.name

    override fun getParameters(): List<SignatureParameter> {
        val factory = RsPsiFactory(config.function.project)
        return config.parameters.map { SignatureParameter(factory, it) }
    }

    override fun getParametersCount(): Int = config.parameters.size // TODO: self
    override fun getMethod(): PsiElement = config.function

    override fun getVisibility(): String = ""

    /**
     * This needs to be false, because the default dialog only offers combo boxes for visibility, but we need
     * arbitrary strings.
     */
    override fun canChangeVisibility(): Boolean = false

    override fun canChangeParameters(): Boolean = true
    override fun canChangeName(): Boolean = true
    override fun canChangeReturnType(): MethodDescriptor.ReadWriteOption = MethodDescriptor.ReadWriteOption.ReadWrite
}

private class ModelItem(val function: RsFunction, parameter: SignatureParameter)
    : ParameterTableModelItemBase<SignatureParameter>(
    parameter,
    createTypeCodeFragment(function, parameter.parameter.type),
    createTypeCodeFragment(function, parameter.parameter.type),
) {
    override fun isEllipsisType(): Boolean = false
}

private class TableModel(val descriptor: SignatureDescriptor, val onUpdate: () -> Unit)
    : ParameterTableModelBase<SignatureParameter, ModelItem>(
    descriptor.function,
    descriptor.function,
    NameColumn<SignatureParameter, ModelItem>(descriptor.function.project, "Pattern"),
    SignatureTypeColumn(descriptor)
) {
    private val factory: RsPsiFactory = RsPsiFactory(descriptor.function.project)

    init {
        addTableModelListener {
            onUpdate()
        }
    }

    override fun createRowItem(parameterInfo: SignatureParameter?): ModelItem {
        val parameter = if (parameterInfo == null) {
            val newParameter = createNewParameter(descriptor)
            descriptor.config.parameters.add(newParameter)
            SignatureParameter(factory, newParameter)
        } else parameterInfo

        return ModelItem(descriptor.function, parameter)
    }

    override fun removeRow(index: Int) {
        descriptor.config.parameters.removeAt(index)
        super.removeRow(index)
    }

    /**
     * Swap order of parameters.
     */
    override fun fireTableRowsUpdated(firstRow: Int, lastRow: Int) {
        val parameters = descriptor.config.parameters
        val tmp = parameters[firstRow]
        parameters[firstRow] = parameters[lastRow]
        parameters[lastRow] = tmp

        super.fireTableRowsUpdated(firstRow, lastRow)
    }

    private fun createNewParameter(descriptor: SignatureDescriptor): Parameter {
        val pat = factory.createPat("p${descriptor.parametersCount}")
        return Parameter(pat)
    }

    private class SignatureTypeColumn(val descriptor: SignatureDescriptor)
        : TypeColumn<SignatureParameter, ModelItem>(descriptor.function.project, RsFileType) {
        override fun setValue(item: ModelItem?, value: PsiCodeFragment?) {
            val fragment = value as? RsTypeReferenceCodeFragment ?: return
            if (item != null) {
                val type = fragment.typeReference?.type
                if (type != null) {
                    item.parameter.parameter.changeType(type)
                }
            }
        }

        override fun valueOf(item: ModelItem?): PsiCodeFragment? {
            if (item == null) return null
            return createTypeCodeFragment(descriptor.function, item.parameter.parameter.displayType)
        }
    }
}

private class ChangeSignatureDialog(project: Project, descriptor: SignatureDescriptor) :
    ChangeSignatureDialogBase<SignatureParameter,
        RsFunction,
        String,
        SignatureDescriptor,
        ModelItem,
        TableModel
        >(project, descriptor, false, descriptor.method) {
    private val config: RsChangeFunctionSignatureConfig
        get() = myMethod.config

    private var visibilityComboBox: VisibilityComboBox? = null

    override fun getFileType(): LanguageFileType = RsFileType

    override fun placeReturnTypeBeforeName(): Boolean = false

    override fun createNorthPanel(): JComponent? {
        val panel = super.createNorthPanel() ?: return null
        // Make all two (or three) elements the same size
        myNameField.setPreferredWidth(-1)
        myReturnTypeField.setPreferredWidth(-1)

        if (config.allowsVisibilityChange) {
            val visibilityPanel = JPanel(BorderLayout(0, 2))
            val visibilityLabel = JLabel("Visibility:")
            visibilityPanel.add(visibilityLabel, BorderLayout.NORTH)

            val visibility = VisibilityComboBox(project, config.visibility) { updateSignature() }
            visibilityLabel.labelFor = visibility.component
            visibilityPanel.add(visibility.component, BorderLayout.SOUTH)
            visibilityComboBox = visibility

            // Place visibility before function name and return type
            val layout = panel.layout as GridBagLayout
            val nameConstraints = layout.getConstraints(myNamePanel).clone() as GridBagConstraints
            nameConstraints.gridx = 1
            layout.setConstraints(myNamePanel, nameConstraints)

            val myReturnTypePanel = myReturnTypeField.parent
            val returnTypeConstraints = layout.getConstraints(myReturnTypePanel).clone() as GridBagConstraints
            returnTypeConstraints.gridx = 2
            layout.setConstraints(myReturnTypePanel, returnTypeConstraints)

            val gbc = GridBagConstraints(0, 0, 1, 1, 1.0, 1.0,
                GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL,
                Insets(0, 0, 0, 0),
                0, 0)
            panel.add(visibilityPanel, gbc)
        }
        return panel
    }

    override fun createSouthAdditionalPanel(): JPanel {
        // TODO: only in edition 2018?
        val asyncBox = CheckBox("Async", config.isAsync)
        asyncBox.addChangeListener {
            config.isAsync = asyncBox.isSelected
            updateSignature()
        }
        val unsafeBox = CheckBox("Unsafe", config.isUnsafe)
        unsafeBox.addChangeListener {
            config.isUnsafe = unsafeBox.isSelected
            updateSignature()
        }

        return panel {
            row {
                asyncBox()
                unsafeBox()
            }
        }
    }

    override fun createParametersInfoModel(
        descriptor: SignatureDescriptor
    ): TableModel = TableModel(descriptor, ::updateSignature)

    override fun createRefactoringProcessor(): BaseRefactoringProcessor =
        RsChangeSignatureProcessor(project, config.createChangeInfo())

    override fun createReturnTypeCodeFragment(): PsiCodeFragment {
        val fragment = createTypeCodeFragment(myMethod.function, myMethod.function.returnType)
        val document = fragment.document!!
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        })
        return fragment
    }

    override fun createCallerChooser(
        title: String?,
        treeToReuse: Tree?,
        callback: Consumer<MutableSet<RsFunction>>?
    ): CallerChooserBase<RsFunction>? = null

    override fun validateAndCommitData(): String? = updateConfig()

    override fun updateSignature() {
        updateConfig()
        super.updateSignature()
    }

    /**
     * Updates the config from UI elements that are not updated automatically and returns a potential error message.
     */
    private fun updateConfig(): String? {
        if (myNameField != null) {
            val functionName = myNameField.text
            if (validateName(functionName)) {
                config.name = functionName
            } else return "Function name must be a valid Rust identifier"
        }

        if (myReturnTypeField != null) {
            val returnTypeText = myReturnTypeField.text
            val returnType = if (returnTypeText.isBlank()) {
                TyUnit
            } else {
                (myReturnTypeCodeFragment as? RsTypeReferenceCodeFragment)?.typeReference?.type
            }
            if (returnType != null) {
                config.returnType = returnType
            } else {
                return "Function return type must be a valid Rust type"
            }
        }

        val visField = visibilityComboBox
        if (visField != null) {
            if (visField.hasValidVisibility) {
                config.visibility = visField.visibility
            } else {
                return "Function visibility must be a valid visibility specifier"
            }
        }

        return null
    }

    override fun calculateSignature(): String = config.signature()

    /**
     * This is unused, since visibility is handled with a custom input.
     */
    override fun createVisibilityControl(): ComboBoxVisibilityPanel<String> =
        object : ComboBoxVisibilityPanel<String>("", arrayOf()) {}
}

private fun createTypeCodeFragment(context: RsElement, type: Ty?): PsiCodeFragment = RsTypeReferenceCodeFragment(
    context.project,
    type?.render(includeTypeArguments = true, includeLifetimeArguments = true, skipUnchangedDefaultTypeArguments = true)
        .orEmpty(),
    context = context
)

private fun validateName(name: String): Boolean = name.isNotBlank() && isValidRustVariableIdentifier(name)

private class VisibilityComboBox(project: Project, initialVis: RsVis?, onChange: () -> Unit) {
    private val combobox: ComboBox<String> = ComboBox<String>(createVisibilityHints(initialVis), 80)
    private val factory: RsPsiFactory = RsPsiFactory(project)

    val component: JComponent = combobox

    val hasValidVisibility: Boolean
        get() = (combobox.selectedItem as String).isBlank() || visibility != null
    val visibility: RsVis?
        get() = factory.tryCreateVis(combobox.selectedItem as String)

    init {
        combobox.isEditable = true
        combobox.selectedItem = initialVis?.text.orEmpty()
        combobox.addActionListener {
            onChange()
        }
    }
}

private fun createVisibilityHints(initialVis: RsVis?): Array<String> =
    setOf(initialVis?.text.orEmpty(), "", "pub", "pub(crate)", "pub(super)").toTypedArray()