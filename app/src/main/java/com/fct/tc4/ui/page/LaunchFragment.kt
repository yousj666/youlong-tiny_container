// LaunchFragment.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.fct.tc4.databinding.Tc4FragmentLaunchBinding

/**
 * 容器主界面第 1 页（原"快捷方式"页）。
 *
 * 游龙改版：删除原来的快捷命令管理，改为一个大大的"启动"按钮，
 * 点击后与点击右上角菜单里的"进入图形界面"效果一致（启动桌面 GUI）。
 */
class LaunchFragment : Fragment() {

    private var _binding: Tc4FragmentLaunchBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = Tc4FragmentLaunchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.launchButton.setOnClickListener {
            (requireParentFragment() as? ContainerMainFragment)?.onEnterGui()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
