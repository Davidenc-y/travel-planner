'use client';

import { Component, ReactNode } from 'react';
import { ErrorState } from '@/components/ui/error-state';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/** 全局渲染错误边界（F91）：渲染异常不白屏，展示错误态 + 重试 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  render() {
    if (this.state.error) {
      return (
        <ErrorState
          message={this.state.error.message || '页面渲染异常'}
          onReset={() => this.setState({ error: null })}
        />
      );
    }
    return this.props.children;
  }
}
