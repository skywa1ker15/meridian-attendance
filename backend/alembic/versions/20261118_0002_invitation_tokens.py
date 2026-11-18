"""Invitation tokens (employees set their own password and phone)

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-18
"""
from __future__ import annotations

import sqlalchemy as sa

from alembic import op

revision = '0002'
down_revision = '0001'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'invitation_tokens',
        sa.Column('user_id', sa.UUID(), nullable=False),
        sa.Column('token_hash', sa.String(length=64), nullable=False),
        sa.Column('created_by_id', sa.UUID(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.text('now()'), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('used_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('id', sa.UUID(), nullable=False),
        sa.ForeignKeyConstraint(['created_by_id'], ['users.id'], name=op.f('fk_invitation_tokens_created_by_id_users'), ondelete='SET NULL'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], name=op.f('fk_invitation_tokens_user_id_users'), ondelete='CASCADE'),
        sa.PrimaryKeyConstraint('id', name=op.f('pk_invitation_tokens')),
        sa.UniqueConstraint('token_hash', name=op.f('uq_invitation_tokens_token_hash')),
    )
    op.create_index(op.f('ix_invitation_tokens_user_id'), 'invitation_tokens', ['user_id'], unique=False)


def downgrade() -> None:
    op.drop_index(op.f('ix_invitation_tokens_user_id'), table_name='invitation_tokens')
    op.drop_table('invitation_tokens')
